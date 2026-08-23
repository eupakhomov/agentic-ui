package de.pamir.claude.ui.journal;

import de.pamir.claude.ui.journal.EventJournal.JournalEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Fan-out of journaled events to any number of live subscribers per session
 * (typically WebSocket connections). Sessions run fine with zero subscribers.
 */
@Component
public class SessionEventBus {

	private final Map<UUID, Set<Consumer<JournalEvent>>> subscribers = new ConcurrentHashMap<>();

	public void publish(UUID sessionId, JournalEvent event) {
		Set<Consumer<JournalEvent>> subs = subscribers.get(sessionId);
		if (subs != null) {
			for (Consumer<JournalEvent> sub : subs) {
				sub.accept(event);
			}
		}
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
