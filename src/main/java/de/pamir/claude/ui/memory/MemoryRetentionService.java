package de.pamir.claude.ui.memory;

import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.session.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Prunes the raw journal of CLOSED, reflected sessions once memory.retention-days old — the
 * episode + semantic memory a reflection wrote is the durable record from that point on (decision
 * 8, docs/plan/phase-5.3-memory-reflection.md). Default retention is 0 (never prune); this is a
 * policy knob, not an aggressive-deletion default.
 */
@Service
public class MemoryRetentionService {

	private static final Logger log = LoggerFactory.getLogger(MemoryRetentionService.class);

	private final SettingsService settings;
	private final SessionRepository sessions;
	private final EventJournal journal;

	public MemoryRetentionService(SettingsService settings, SessionRepository sessions, EventJournal journal) {
		this.settings = settings;
		this.sessions = sessions;
		this.journal = journal;
	}

	@Scheduled(fixedDelay = 3_600_000)
	void tick() {
		int days = settings.memoryRetentionDays();
		if (days <= 0) {
			return;
		}
		Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
		for (var session : sessions.findPrunableClosed(cutoff)) {
			if (journal.lastSeq(session.id()) == 0) {
				continue; // already pruned in an earlier tick
			}
			int deleted = journal.pruneAll(session.id());
			log.info("memory retention: pruned {} journal row(s) for closed+reflected session {}", deleted, session.id());
		}
	}
}
