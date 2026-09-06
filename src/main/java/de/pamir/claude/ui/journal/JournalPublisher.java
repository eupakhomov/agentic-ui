package de.pamir.claude.ui.journal;

import de.pamir.claude.ui.journal.EventJournal.JournalEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Facade for the "journal assigns seq; subscribers see exactly what replay will" idiom —
 * append then publish, always together in that order, previously hand-rolled at every call
 * site (see docs/plan/phase-9-production-hardening.md G4).
 */
@Component
public class JournalPublisher {

	private final EventJournal journal;
	private final SessionEventBus bus;

	public JournalPublisher(EventJournal journal, SessionEventBus bus) {
		this.journal = journal;
		this.bus = bus;
	}

	public JournalEvent record(UUID sessionId, String type, JsonNode payload) {
		JournalEvent event = journal.append(sessionId, type, payload);
		bus.publish(sessionId, event);
		return event;
	}
}
