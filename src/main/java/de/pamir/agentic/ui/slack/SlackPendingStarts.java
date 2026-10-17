package de.pamir.agentic.ui.slack;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code @bot dev <service> <branch>} without a prompt: the bot asks for one in the thread under the
 * message, and the requester's next reply in that thread is the prompt. This remembers those open
 * questions — in memory, bounded, and short-lived (an answer an hour later should not quietly start a
 * session), and only the person who asked can answer.
 */
final class SlackPendingStarts {

	static final Duration TTL = Duration.ofMinutes(15);
	private static final int CAPACITY = 50;

	private record Entry(SlackCommandHandler.AwaitedPrompt awaited, long expiresAtMillis) {
	}

	private final Clock clock;
	private final Map<String, Entry> byThread = new LinkedHashMap<>();

	SlackPendingStarts(Clock clock) {
		this.clock = clock;
	}

	private static String key(String channel, String threadTs) {
		return channel + ":" + threadTs;
	}

	synchronized void put(String channel, String threadTs, SlackCommandHandler.AwaitedPrompt awaited) {
		long now = clock.millis();
		byThread.values().removeIf(e -> e.expiresAtMillis() <= now);
		while (byThread.size() >= CAPACITY) {
			byThread.remove(byThread.keySet().iterator().next()); // the oldest
		}
		byThread.put(key(channel, threadTs), new Entry(awaited, now + TTL.toMillis()));
	}

	/** Removes and returns the open question for this thread — only if it is still fresh and {@code user} is who asked. */
	synchronized Optional<SlackCommandHandler.AwaitedPrompt> take(String channel, String threadTs, String user) {
		String k = key(channel, threadTs);
		Entry entry = byThread.get(k);
		if (entry == null) {
			return Optional.empty();
		}
		if (entry.expiresAtMillis() <= clock.millis()) {
			byThread.remove(k);
			return Optional.empty();
		}
		if (!entry.awaited().user().equals(user)) {
			return Optional.empty(); // someone else's question: leave it for them
		}
		byThread.remove(k);
		return Optional.of(entry.awaited());
	}
}
