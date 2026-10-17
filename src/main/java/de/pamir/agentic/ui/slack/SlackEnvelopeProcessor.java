package de.pamir.agentic.ui.slack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The transport-independent half of Socket Mode: turns raw text frames into acknowledged,
 * deduplicated {@link SlackEvent}s. Kept free of any WebSocket type so redelivery, ordering and
 * malformed input can be tested without a connection.
 *
 * <p>Slack delivers at least once: an envelope not acked within ~3 s is resent, and envelopes can
 * repeat after a reconnect. So every actionable envelope is acked <em>first</em>, then deduplicated
 * on a stable key (the event's own id, or trigger + action timestamp for a click), then handed to
 * {@code executor} — a single-threaded one in production, so events run in arrival order and a
 * burst of thread replies can't turn into reordered prompts.
 */
final class SlackEnvelopeProcessor {

	private static final Logger log = LoggerFactory.getLogger(SlackEnvelopeProcessor.class);

	private static final int DEDUPE_CAPACITY = 2048;

	private final ObjectMapper mapper;
	private final Consumer<String> ackSender;
	private final Executor executor;
	private final SlackEvent.Listener listener;
	private final Runnable onDisconnectRequested;
	private final RecentKeys seen = new RecentKeys(DEDUPE_CAPACITY);

	SlackEnvelopeProcessor(ObjectMapper mapper, Consumer<String> ackSender, Executor executor,
						   SlackEvent.Listener listener, Runnable onDisconnectRequested) {
		this.mapper = mapper;
		this.ackSender = ackSender;
		this.executor = executor;
		this.listener = listener;
		this.onDisconnectRequested = onDisconnectRequested;
	}

	void onFrame(String text) {
		JsonNode envelope;
		try {
			envelope = mapper.readTree(text);
		} catch (RuntimeException e) {
			log.warn("slack socket: ignoring unparseable frame ({} chars)", text == null ? 0 : text.length());
			return;
		}
		String type = envelope.path("type").asText("");
		switch (type) {
			case "hello" -> log.info("slack socket: ready");
			case "disconnect" -> {
				String reason = envelope.path("reason").asText("?");
				if ("warning".equals(reason)) {
					// advance notice only; the actual refresh_requested follows shortly
					log.info("slack socket: connection will be refreshed soon");
				} else {
					log.info("slack socket: server closed the connection ({}), reconnecting", reason);
					onDisconnectRequested.run();
				}
			}
			case "events_api", "interactive" -> handleDelivery(type, envelope);
			default -> log.debug("slack socket: ignoring '{}' frame", type);
		}
	}

	private void handleDelivery(String type, JsonNode envelope) {
		String envelopeId = envelope.path("envelope_id").asText("");
		if (envelopeId.isBlank()) {
			return;
		}
		ackSender.accept(mapper.createObjectNode().put("envelope_id", envelopeId).toString());
		JsonNode payload = envelope.path("payload");
		String key = dedupeKey(type, payload, envelopeId);
		if (!seen.firstTime(key)) {
			log.debug("slack socket: dropping redelivered {} ({})", type, key);
			return;
		}
		SlackEvent event = new SlackEvent(type, key, payload);
		executor.execute(() -> {
			try {
				listener.onEvent(event);
			} catch (RuntimeException e) {
				log.warn("slack socket: handler failed for {} ({}): {}", type, key, e.getMessage());
			}
		});
	}

	static String dedupeKey(String type, JsonNode payload, String envelopeId) {
		if ("events_api".equals(type)) {
			String eventId = payload.path("event_id").asText("");
			if (!eventId.isBlank()) {
				return "event:" + eventId;
			}
		} else {
			String triggerId = payload.path("trigger_id").asText("");
			String actionTs = payload.path("actions").path(0).path("action_ts").asText("");
			if (!triggerId.isBlank() && !actionTs.isBlank()) {
				return "action:" + triggerId + ":" + actionTs;
			}
		}
		return "envelope:" + envelopeId;
	}

	/** Bounded "have I seen this" set; the oldest key is evicted past capacity. */
	static final class RecentKeys {

		private final Map<String, Boolean> keys;

		RecentKeys(int capacity) {
			this.keys = new LinkedHashMap<>(16, 0.75f, false) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
					return size() > capacity;
				}
			};
		}

		synchronized boolean firstTime(String key) {
			return keys.putIfAbsent(key, Boolean.TRUE) == null;
		}
	}
}
