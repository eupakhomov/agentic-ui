package de.pamir.agentic.ui.journal;

import de.pamir.agentic.ui.journal.EventJournal.JournalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fan-out of journaled events to any number of live subscribers per session
 * (typically WebSocket connections). Sessions run fine with zero subscribers.
 */
@Component
public class SessionEventBus {

	private static final Logger log = LoggerFactory.getLogger(SessionEventBus.class);

	private final Map<UUID, Set<Consumer<JournalEvent>>> subscribers = new ConcurrentHashMap<>();
	// every session's events; used by integrations (Slack) that follow all sessions. A failing one must
	// never break journaling or the other subscribers, so each is isolated.
	private final Set<BiConsumer<UUID, JournalEvent>> globalSubscribers = new CopyOnWriteArraySet<>();

	public void publish(UUID sessionId, JournalEvent event) {
		Set<Consumer<JournalEvent>> subs = subscribers.get(sessionId);
		if (subs != null) {
			for (Consumer<JournalEvent> sub : subs) {
				sub.accept(event);
			}
		}
		for (BiConsumer<UUID, JournalEvent> sub : globalSubscribers) {
			try {
				sub.accept(sessionId, event);
			} catch (RuntimeException e) {
				log.warn("global event subscriber failed for {}: {}", event.type(), e.getMessage());
			}
		}
	}

	/** Subscribes to the events of every session. Handlers should return quickly (hand work off). */
	public void subscribeAll(BiConsumer<UUID, JournalEvent> subscriber) {
		globalSubscribers.add(subscriber);
	}

	public void subscribe(UUID sessionId, Consumer<JournalEvent> subscriber) {
		subscribers.computeIfAbsent(sessionId, id -> new CopyOnWriteArraySet<>()).add(subscriber);
	}

	public void unsubscribe(UUID sessionId, Consumer<JournalEvent> subscriber) {
		Set<Consumer<JournalEvent>> subs = subscribers.get(sessionId);
		if (subs != null) {
			subs.remove(subscriber);
		}
	}
}
