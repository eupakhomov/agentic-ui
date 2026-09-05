package de.pamir.claude.ui.session;

import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.journal.TranscriptDigest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Turns a session's transcript into a short handoff brief for 7.3's "continue from" picker
 * — one system-session turn over the same capped digest reflection already uses (the
 * /compact pattern), not a mechanical copy of the transcript. See
 * docs/plan/phase-7-ux-and-orchestration.md 7.3.
 */
@Service
public class HandoffService {

	private static final Duration TIMEOUT = Duration.ofSeconds(45);

	private final SessionService sessionService;
	private final SessionRepository sessions;
	private final EventJournal journal;

	public HandoffService(SessionService sessionService, SessionRepository sessions, EventJournal journal) {
		this.sessionService = sessionService;
		this.sessions = sessions;
		this.journal = journal;
	}

	public String summarize(UUID sessionId) {
		SessionEntity session = sessions.get(sessionId);
		if (journal.lastSeq(sessionId) == 0) {
			throw new IllegalStateException("nothing to hand off — this session's journal is empty or was pruned");
		}
		String digest = TranscriptDigest.render(journal.readAfter(sessionId, 0));
		String prompt = ("You are producing a handoff brief so a new session can continue this work with no other "
				+ "context. Read the transcript digest below (from a session named \"%s\" on branch \"%s\") and "
				+ "write a concise Markdown brief (roughly 1-2 KB) covering: the overall goal, the current state "
				+ "of the work, key decisions made and why, concrete next steps, and any gotchas or dead ends. "
				+ "Write it as direct instructions to the next agent, not a narrative summary. Respond with ONLY "
				+ "the Markdown brief — no commentary, no code fence around the whole thing.\n\n"
				+ "Transcript digest:\n%s").formatted(session.name(), session.branch(), digest);
		String summary = sessionService.runSystemTurn(prompt, TIMEOUT).strip();
		if (summary.isBlank()) {
			throw new IllegalStateException("handoff summary was empty");
		}
		return summary;
	}
}
