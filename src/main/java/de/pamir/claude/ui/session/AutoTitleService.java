package de.pamir.claude.ui.session;

import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.journal.JournalPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.UUID;

/**
 * After a session's first turn, names it if it's still carrying its default (= branch) name.
 * Extracted from SessionService (see docs/plan/phase-9-production-hardening.md S1, merges with
 * P2/O2) — runs the title-generation turn through {@link SystemTurnClient} on the {@code
 * BACKGROUND} lane (see {@link SystemTurnLane}) since it's fire-and-forget from the caller's
 * perspective and shouldn't compete with an interactive caller for the system session lock.
 */
@Service
public class AutoTitleService {

	private static final Logger log = LoggerFactory.getLogger(AutoTitleService.class);

	private final SessionRepository sessions;
	private final EventJournal journal;
	private final SettingsService settings;
	private final SystemTurnClient systemTurnClient;
	private final JournalPublisher journalPublisher;
	private final ObjectMapper mapper;

	public AutoTitleService(SessionRepository sessions, EventJournal journal, SettingsService settings,
							 SystemTurnClient systemTurnClient, JournalPublisher journalPublisher, ObjectMapper mapper) {
		this.sessions = sessions;
		this.journal = journal;
		this.settings = settings;
		this.systemTurnClient = systemTurnClient;
		this.journalPublisher = journalPublisher;
		this.mapper = mapper;
	}

	void maybeAutoTitle(UUID id) {
		SessionEntity session = sessions.find(id).orElse(null);
		if (session == null || !session.name().equals(session.branch())) {
			return;
		}
		var events = journal.readAfter(id, 0);
		long turns = events.stream().filter(e -> e.type().equals("turn_complete")).count();
		if (turns != 1) {
			return;
		}
		String userText = events.stream().filter(e -> e.type().equals("user_message")).findFirst()
				.map(e -> e.payload().path("text").asText()).orElse("");
		if (userText.isBlank()) {
			return;
		}
		Thread.ofVirtual().name("auto-title-" + id).start(() -> {
			try {
				// Routed through the system session (P2) instead of a raw `claude -p` spawn: works
				// on a Codex-only install too, and the turn's cost now lands in the usage dashboard
				// like every other system turn (O2) instead of being invisible.
				String modelOverride = ModelCatalog.byTier(settings.systemProvider(), "cheap").orElse(null);
				String title = systemTurnClient.text(
						"Generate a short title (max 6 words) for a coding session that starts with this request. "
								+ "Output ONLY the title, no quotes:\n\n" + userText.substring(0, Math.min(500, userText.length())),
						modelOverride, SystemTurnLane.BACKGROUND, Duration.ofSeconds(60));
				if (!title.isBlank() && title.length() <= 80) {
					SessionEntity current = sessions.find(id).orElse(null);
					if (current != null && current.name().equals(current.branch())) {
						sessions.updateName(id, title);
						journalPublisher.record(id, "session_renamed",
								mapper.createObjectNode().put("name", title).put("auto", true));
					}
				}
			} catch (RuntimeException e) {
				log.debug("auto-title failed for {}: {}", id, e.getMessage());
			}
		});
	}
}
