package de.pamir.agentic.ui.slack;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Socket Mode connection to Slack on the JDK's own {@link java.net.http.WebSocket} (phase 18,
 * decision 5's fallback — the Slack SDK ships its WebSocket backends as optional dependencies and
 * the Tyrus one speaks the pre-Jakarta {@code javax.websocket} API next to Boot 4's Tomcat).
 * The protocol is small: {@code apps.connections.open} yields a one-shot {@code wss://} URL, frames
 * are JSON envelopes, and each actionable one is acknowledged with {@code {"envelope_id": …}}.
 *
 * <p>One daemon-style loop keeps exactly one connection alive: it reconnects with exponential
 * backoff on failure, immediately when Slack asks (connections are recycled every few hours), and
 * when the line goes silent — we ping every 30 s and treat 75 s without any frame, pong included,
 * as dead. Frames go through {@link SlackEnvelopeProcessor}, which outlives individual
 * connections so a redelivery after a reconnect is still recognised as a duplicate.
 */
@Component
@ConditionalOnProperty(prefix = "agentic-ui.slack", name = "enabled", havingValue = "true")
public class SlackSocket {

	private static final Logger log = LoggerFactory.getLogger(SlackSocket.class);

	private static final long MIN_BACKOFF_MS = 1_000;
	private static final long MAX_BACKOFF_MS = 30_000;
	private static final long PING_EVERY_SECONDS = 30;
	private static final long SILENCE_LIMIT_NANOS = Duration.ofSeconds(75).toNanos();

	private final SlackProperties props;
	private final SlackWebApi api;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final List<SlackEvent.Listener> listeners = new CopyOnWriteArrayList<>();
	// one thread, so events are handled in the order Slack delivered them
	private final ExecutorService dispatch = Executors.newSingleThreadExecutor(
			Thread.ofVirtual().name("slack-dispatch").factory());
	private final SlackEnvelopeProcessor processor;

	private volatile boolean running;
	private volatile Thread loop;
	private volatile WebSocket current;
	private volatile CountDownLatch connectionEnded = new CountDownLatch(0);
	private volatile long lastFrameNanos;
	private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

	public SlackSocket(SlackProperties props, SlackWebApi api, ObjectMapper mapper) {
		this.props = props;
		this.api = api;
		this.processor = new SlackEnvelopeProcessor(mapper, this::sendToCurrent, dispatch,
				event -> listeners.forEach(l -> l.onEvent(event)), () -> connectionEnded.countDown());
	}

	/** Receives every acknowledged, deduplicated delivery, one at a time, in arrival order. */
	public void addListener(SlackEvent.Listener listener) {
		listeners.add(listener);
	}

	public boolean connected() {
		return current != null && connectionEnded.getCount() > 0;
	}

	@EventListener(ApplicationReadyEvent.class)
	void start() {
		if (!props.hasTokens()) {
			log.warn("slack: enabled but bot-token/app-token are not both set (AGENTIC_UI_SLACK_BOT_TOKEN / "
					+ "AGENTIC_UI_SLACK_APP_TOKEN) — not connecting");
			return;
		}
		if (props.channel().isBlank()) {
			log.warn("slack: no channel configured (AGENTIC_UI_SLACK_CHANNEL) — nothing can be posted");
		}
		if (props.allowedUsers().isEmpty()) {
			log.warn("slack: allowed-users is empty — every Slack message and button click will be ignored");
		}
		running = true;
		loop = Thread.ofVirtual().name("slack-socket").start(this::runLoop);
	}

	@PreDestroy
	void stop() {
		running = false;
		connectionEnded.countDown();
		Thread t = loop;
		if (t != null) {
			t.interrupt();
		}
		abortCurrent();
		dispatch.shutdownNow();
	}

	private void runLoop() {
		verifyBotToken();
		long backoffMs = MIN_BACKOFF_MS;
		while (running) {
			try {
				runConnection();
				backoffMs = MIN_BACKOFF_MS;
				sleep(MIN_BACKOFF_MS);
			} catch (InterruptedException e) {
				return;
			} catch (Exception e) {
				if (!running) {
					return;
				}
				log.warn("slack socket: connection failed: {} (retrying in {}s)", e.getMessage(), backoffMs / 1000);
				try {
					sleep(backoffMs);
				} catch (InterruptedException ie) {
					return;
				}
				backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
			}
		}
	}

	private void verifyBotToken() {
		try {
			JsonNode auth = api.authTest(props.botToken());
			log.info("slack: bot '{}' in workspace '{}'", auth.path("user").asText("?"), auth.path("team").asText("?"));
		} catch (RuntimeException e) {
			log.error("slack: bot token check failed ({}) — inbound may work but posting will not", e.getMessage());
		}
	}

	/** Connects once and blocks until that connection ends, for any reason. */
	private void runConnection() throws Exception {
		String url = api.openSocketUrl(props.appToken());
		CountDownLatch ended = new CountDownLatch(1);
		connectionEnded = ended;
		lastFrameNanos = System.nanoTime();
		synchronized (this) {
			sendChain = CompletableFuture.completedFuture(null);
		}
		WebSocket ws = http.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.buildAsync(URI.create(url), new FrameListener(ended))
				.get(15, TimeUnit.SECONDS);
		current = ws;
		log.info("slack socket: connected");
		try {
			while (running && !ended.await(PING_EVERY_SECONDS, TimeUnit.SECONDS)) {
				if (System.nanoTime() - lastFrameNanos > SILENCE_LIMIT_NANOS) {
					log.warn("slack socket: no traffic for {}s, reconnecting", SILENCE_LIMIT_NANOS / 1_000_000_000L);
					break;
				}
				sendPing(ws);
			}
		} finally {
			current = null;
			ws.abort();
			log.info("slack socket: disconnected");
		}
	}

	private void sleep(long ms) throws InterruptedException {
		Thread.sleep(ms);
	}

	private void abortCurrent() {
		WebSocket ws = current;
		if (ws != null) {
			ws.abort();
		}
	}

	/** Sends are chained: {@link WebSocket#sendText} must not overlap a previous unfinished send. */
	private synchronized void sendToCurrent(String text) {
		WebSocket ws = current;
		if (ws == null) {
			log.warn("slack socket: dropping ack, no open connection");
			return;
		}
		sendChain = sendChain.handle((v, t) -> null)
				.thenCompose(v -> ws.sendText(text, true))
				.whenComplete((v, t) -> {
					if (t != null) {
						log.warn("slack socket: ack failed: {}", t.getMessage());
					}
				});
	}

	private synchronized void sendPing(WebSocket ws) {
		sendChain = sendChain.handle((v, t) -> null)
				.thenCompose(v -> ws.sendPing(ByteBuffer.allocate(0)))
				.whenComplete((v, t) -> {
					if (t != null) {
						log.debug("slack socket: ping failed: {}", t.getMessage());
					}
				});
	}

	private final class FrameListener implements WebSocket.Listener {

		private final StringBuilder partial = new StringBuilder();
		private final CountDownLatch ended;

		FrameListener(CountDownLatch ended) {
			this.ended = ended;
		}

		@Override
		public CompletableFuture<?> onText(WebSocket ws, CharSequence data, boolean last) {
			partial.append(data);
			if (last) {
				String frame = partial.toString();
				partial.setLength(0);
				lastFrameNanos = System.nanoTime();
				try {
					processor.onFrame(frame);
				} catch (RuntimeException e) {
					log.warn("slack socket: frame handling failed: {}", e.getMessage());
				}
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletableFuture<?> onPing(WebSocket ws, ByteBuffer message) {
			lastFrameNanos = System.nanoTime();
			ws.request(1);
			return null;
		}

		@Override
		public CompletableFuture<?> onPong(WebSocket ws, ByteBuffer message) {
			lastFrameNanos = System.nanoTime();
			ws.request(1);
			return null;
		}

		@Override
		public CompletableFuture<?> onClose(WebSocket ws, int statusCode, String reason) {
			log.info("slack socket: closed by server ({})", statusCode);
			ended.countDown();
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			log.warn("slack socket: error: {}", error.getMessage());
			ended.countDown();
		}
	}
}
