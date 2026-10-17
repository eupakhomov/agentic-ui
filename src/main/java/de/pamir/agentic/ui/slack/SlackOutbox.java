package de.pamir.agentic.ui.slack;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Everything the mirror sends to Slack goes through here, on one worker thread, so a Slack outage
 * or a 429 can never block (or fail) a session — callers enqueue and move on.
 *
 * <ul>
 *   <li><b>Pacing:</b> at least {@code gap} between calls (Slack allows about one message per second
 *       per channel), so a burst of events turns into a steady trickle rather than a 429 storm.</li>
 *   <li><b>Retry:</b> a rate-limited or transiently failing call is retried up to {@value
 *       #MAX_ATTEMPTS} times, honoring {@code Retry-After}; a permanent refusal ({@code
 *       not_in_channel}, {@code invalid_auth}, …) is logged and dropped.</li>
 *   <li><b>Pressure:</b> posts are FIFO and bounded. When the queue is full the oldest
 *       <em>non-critical</em> post is dropped to make room; a critical post (a permission, plan or
 *       question somebody is waiting on) is never dropped. Parent-message updates are coalesced
 *       by key — at most one pending per session — and sent only when the FIFO is empty, so they
 *       are the first thing to give way.</li>
 *   <li><b>Fresh content:</b> a message body is a {@link Supplier} evaluated at send time, so a
 *       coalesced update always renders the latest state, not the state when it was enqueued.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "artus.slack", name = "enabled", havingValue = "true")
public class SlackOutbox {

	private static final Logger log = LoggerFactory.getLogger(SlackOutbox.class);

	static final int MAX_ATTEMPTS = 5;
	private static final Duration DEFAULT_GAP = Duration.ofMillis(1100);
	private static final int DEFAULT_CAPACITY = 200;
	private static final long ERROR_LOG_EVERY_MS = 60_000;

	@FunctionalInterface
	interface Sleeper {
		void sleep(Duration d) throws InterruptedException;
	}

	/** One queued call; {@code send} returns the result (a message {@code ts}, or null). */
	private record Op(String what, boolean critical, SendFn send, CompletableFuture<String> result) {
	}

	@FunctionalInterface
	private interface SendFn {
		String send(SlackMessaging api);
	}

	private final SlackMessaging api;
	private final Duration gap;
	private final Sleeper sleeper;
	private final int capacity;

	private final Object lock = new Object();
	private final Deque<Op> fifo = new ArrayDeque<>();
	private final Map<Object, Op> updates = new LinkedHashMap<>();
	private final Map<String, Long> lastErrorLog = new HashMap<>();
	private boolean running = true;
	private Thread worker;

	@Autowired
	public SlackOutbox(SlackMessaging api) {
		this(api, DEFAULT_GAP, DEFAULT_CAPACITY, d -> Thread.sleep(d.toMillis()));
	}

	SlackOutbox(SlackMessaging api, Duration gap, int capacity, Sleeper sleeper) {
		this.api = api;
		this.gap = gap;
		this.capacity = capacity;
		this.sleeper = sleeper;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		synchronized (lock) {
			if (worker == null) {
				worker = Thread.ofVirtual().name("slack-outbox").start(this::loop);
			}
		}
	}

	@PreDestroy
	public void stop() {
		synchronized (lock) {
			running = false;
			lock.notifyAll();
		}
	}

	// ------------------------------------------------------------------ enqueue

	/** Queues a post; completes with the new message's {@code ts}. */
	public CompletableFuture<String> post(String channel, String threadTs, boolean critical,
										  Supplier<SlackMessaging.SlackMessage> message) {
		return enqueue(new Op("post", critical,
				a -> a.postMessage(channel, threadTs, message.get()), new CompletableFuture<>()));
	}

	/** Queues an uploaded snippet into a thread. */
	public CompletableFuture<String> snippet(String channel, String threadTs, String title, String content) {
		return enqueue(new Op("snippet", false, a -> {
			a.uploadSnippet(channel, threadTs, title, content);
			return null;
		}, new CompletableFuture<>()));
	}

	/** Queues an emoji reaction. */
	public CompletableFuture<String> react(String channel, String ts, String emoji) {
		return enqueue(new Op("reaction", false, a -> {
			a.addReaction(channel, ts, emoji);
			return null;
		}, new CompletableFuture<>()));
	}

	/**
	 * Queues an in-place message update unless one with the same {@code key} is already waiting
	 * (it will pick up the latest content when it runs). Returns whether this call queued it.
	 */
	public boolean update(Object key, String channel, String ts, Supplier<SlackMessaging.SlackMessage> message) {
		synchronized (lock) {
			if (updates.containsKey(key)) {
				return false;
			}
			updates.put(key, new Op("update", false, a -> {
				a.updateMessage(channel, ts, message.get());
				return null;
			}, new CompletableFuture<>()));
			lock.notifyAll();
			return true;
		}
	}

	private CompletableFuture<String> enqueue(Op op) {
		synchronized (lock) {
			if (fifo.size() >= capacity) {
				Iterator<Op> it = fifo.iterator();
				while (it.hasNext()) {
					Op candidate = it.next();
					if (!candidate.critical()) {
						it.remove();
						candidate.result().completeExceptionally(new IllegalStateException("dropped: slack queue full"));
						log.warn("slack: queue full ({}), dropped the oldest non-critical {}", capacity, candidate.what());
						break;
					}
				}
			}
			fifo.addLast(op);
			lock.notifyAll();
		}
		return op.result();
	}

	// ------------------------------------------------------------------ worker

	private void loop() {
		try {
			while (true) {
				Op op = next();
				if (op == null) {
					return;
				}
				run(op);
				sleeper.sleep(gap);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private Op next() throws InterruptedException {
		synchronized (lock) {
			while (running) {
				Op op = fifo.pollFirst();
				if (op == null && !updates.isEmpty()) {
					Iterator<Op> it = updates.values().iterator();
					op = it.next();
					it.remove();
				}
				if (op != null) {
					return op;
				}
				lock.wait();
			}
			return null;
		}
	}

	private void run(Op op) throws InterruptedException {
		for (int attempt = 1; ; attempt++) {
			try {
				op.result().complete(op.send().send(api));
				return;
			} catch (SlackApiException e) {
				if (!e.transientError() || attempt >= MAX_ATTEMPTS) {
					logFailure(op, e);
					op.result().completeExceptionally(e);
					return;
				}
				sleeper.sleep(e.retryAfter() != null ? e.retryAfter() : Duration.ofSeconds(2L * attempt));
			} catch (RuntimeException e) {
				// e.g. a message Supplier that failed to render — never retry that, never kill the worker
				log.warn("slack: {} failed: {}", op.what(), e.getMessage());
				op.result().completeExceptionally(e);
				return;
			}
		}
	}

	/** Permanent failures repeat (a misconfigured channel fails every post); log each code once a minute. */
	private void logFailure(Op op, SlackApiException e) {
		long now = System.currentTimeMillis();
		Long last = lastErrorLog.get(e.code());
		if (last == null || now - last >= ERROR_LOG_EVERY_MS) {
			lastErrorLog.put(e.code(), now);
			log.warn("slack: {} dropped: {}{}", op.what(), e.getMessage(), hint(e.code()));
		}
	}

	private static String hint(String code) {
		return switch (code) {
			case "not_in_channel" -> " — invite the bot to the channel (/invite @<bot>)";
			case "channel_not_found" -> " — check ARTUS_SLACK_CHANNEL (the channel ID, not its name)";
			case "invalid_auth", "token_revoked", "account_inactive" -> " — check ARTUS_SLACK_BOT_TOKEN";
			case "missing_scope" -> " — add the scope in the Slack app and reinstall it";
			case "is_archived" -> " — the channel is archived";
			default -> "";
		};
	}
}
