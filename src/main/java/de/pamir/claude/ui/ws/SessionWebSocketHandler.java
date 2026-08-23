package de.pamir.claude.ui.ws;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.journal.EventJournal.JournalEvent;
import de.pamir.claude.ui.journal.SessionEventBus;
import de.pamir.claude.ui.session.SessionRepository;
import de.pamir.claude.ui.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One WS connection = one subscriber to one session. On connect: replay journal
 * events after {@code afterSeq}, then a replay_complete marker, then live events.
 * Live events arriving during replay are buffered and seq-deduplicated.
 */
@Component
public class SessionWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

	public static final String SUBPROTOCOL = "claude-ui.v1";

	private static final Logger log = LoggerFactory.getLogger(SessionWebSocketHandler.class);
	private static final int SEND_TIME_LIMIT_MS = 10_000;

	private final SessionService service;
	private final SessionRepository sessions;
	private final EventJournal journal;
	private final SessionEventBus bus;
	private final ObjectMapper mapper;
	private final int sendBufferLimit;

	public SessionWebSocketHandler(SessionService service, SessionRepository sessions, EventJournal journal,
								   SessionEventBus bus, ObjectMapper mapper,
								   de.pamir.claude.ui.config.AppProperties props) {
		this.service = service;
		this.sessions = sessions;
		this.journal = journal;
		this.bus = bus;
		this.mapper = mapper;
		this.sendBufferLimit = props.wsSendBufferBytes();
	}

	@Override
	public List<String> getSubProtocols() {
		return List.of(SUBPROTOCOL);
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
		UUID sessionId = sessionIdOf(rawSession.getUri());
		if (sessionId == null || sessions.find(sessionId).isEmpty()) {
			rawSession.close(CloseStatus.POLICY_VIOLATION.withReason("unknown session"));
			return;
		}
		long afterSeq = afterSeqOf(rawSession.getUri());
		var ws = new ConcurrentWebSocketSessionDecorator(rawSession, SEND_TIME_LIMIT_MS, sendBufferLimit);

		Subscriber subscriber = new Subscriber(ws);
		rawSession.getAttributes().put("sessionId", sessionId);
		rawSession.getAttributes().put("subscriber", subscriber);
		bus.subscribe(sessionId, subscriber);

		// replay after subscribing so nothing falls between journal read and live stream
		List<JournalEvent> history = journal.readAfter(sessionId, afterSeq);
		long lastSent = afterSeq;
		for (JournalEvent event : history) {
			subscriber.sendDirect(event);
			lastSent = event.seq();
		}
		ObjectNode marker = mapper.createObjectNode();
		marker.put("seq", lastSent).put("type", "replay_complete");
		marker.putObject("payload").put("lastSeq", lastSent);
		ws.sendMessage(new TextMessage(marker.toString()));
		subscriber.goLive(lastSent);
		log.debug("ws subscribed to {} (replayed {} events)", sessionId, history.size());
	}

	@Override
	protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
		UUID sessionId = (UUID) ws.getAttributes().get("sessionId");
		try {
			JsonNode command = mapper.readTree(message.getPayload());
			switch (command.path("type").asText()) {
				case "user_message" -> service.sendUserMessage(sessionId, command.path("text").asText());
				case "permission_response" -> service.respondPermission(sessionId, command);
				case "interrupt" -> service.interrupt(sessionId);
				case "set_permission_mode" -> service.setPermissionMode(sessionId, command.path("mode").asText());
				default -> sendError(ws, "unknown command type: " + command.path("type").asText());
			}
		} catch (Exception e) {
			sendError(ws, e.getMessage());
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
		UUID sessionId = (UUID) ws.getAttributes().get("sessionId");
		Subscriber subscriber = (Subscriber) ws.getAttributes().get("subscriber");
		if (sessionId != null && subscriber != null) {
			bus.unsubscribe(sessionId, subscriber);
		}
	}

	private void sendError(WebSocketSession ws, String message) {
		try {
			ObjectNode error = mapper.createObjectNode();
			error.put("type", "command_error");
			error.putObject("payload").put("message", message);
			ws.sendMessage(new TextMessage(error.toString()));
		} catch (IOException e) {
			log.debug("failed to send ws error: {}", e.getMessage());
		}
	}

	private static UUID sessionIdOf(URI uri) {
		if (uri == null) {
			return null;
		}
		String path = uri.getPath();
		try {
			return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static long afterSeqOf(URI uri) {
		String query = uri == null ? null : uri.getQuery();
		if (query != null) {
			for (String param : query.split("&")) {
				if (param.startsWith("afterSeq=")) {
					try {
						return Long.parseLong(param.substring("afterSeq=".length()));
					} catch (NumberFormatException ignored) {
						return 0;
					}
				}
			}
		}
		return 0;
	}

	/** Buffers live events during replay; strictly seq-increasing sends afterwards. */
	private final class Subscriber implements Consumer<JournalEvent> {

		private final WebSocketSession ws;
		private final Deque<JournalEvent> pendingDuringReplay = new ArrayDeque<>();
		private boolean live;
		private long lastSentSeq;

		private Subscriber(WebSocketSession ws) {
			this.ws = ws;
		}

		@Override
		public synchronized void accept(JournalEvent event) {
			if (!live) {
				pendingDuringReplay.addLast(event);
				return;
			}
			sendIfNew(event);
		}

		synchronized void sendDirect(JournalEvent event) {
			sendIfNew(event);
		}

		synchronized void goLive(long replayedUpTo) {
			lastSentSeq = Math.max(lastSentSeq, replayedUpTo);
			live = true;
			while (!pendingDuringReplay.isEmpty()) {
				sendIfNew(pendingDuringReplay.pollFirst());
			}
		}

		private void sendIfNew(JournalEvent event) {
			if (event.seq() <= lastSentSeq) {
				return;
			}
			lastSentSeq = event.seq();
			try {
				ObjectNode envelope = mapper.createObjectNode();
				envelope.put("seq", event.seq());
				envelope.put("ts", event.ts().toString());
				envelope.put("type", event.type());
				envelope.set("payload", event.payload());
				ws.sendMessage(new TextMessage(envelope.toString()));
			} catch (IOException | IllegalStateException e) {
				// slow or dead consumer: close; the client reconnects with afterSeq
				try {
					ws.close(CloseStatus.SERVICE_OVERLOAD);
				} catch (IOException ignored) {
					// already closed
				}
			}
		}
	}
}
