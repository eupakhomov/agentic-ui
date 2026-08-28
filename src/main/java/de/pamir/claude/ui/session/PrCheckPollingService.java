package de.pamir.claude.ui.session;

import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitOpsService;
import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.journal.SessionEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Polls gh for CI status on any session with an open PR, journaling a pr_status_changed
 * event whenever the aggregate status changes. Ticks every 30s regardless of the
 * configured interval, so raising/lowering it in Settings takes effect on the next tick
 * without a restart — see SettingsService.prCheckPollIntervalSeconds.
 */
@Service
public class PrCheckPollingService {

	private static final Logger log = LoggerFactory.getLogger(PrCheckPollingService.class);

	private final SessionRepository sessions;
	private final SettingsService settings;
	private final GitOpsService gitOps;
	private final EventJournal journal;
	private final SessionEventBus bus;
	private final ObjectMapper mapper;

	public PrCheckPollingService(SessionRepository sessions, SettingsService settings, GitOpsService gitOps,
								  EventJournal journal, SessionEventBus bus, ObjectMapper mapper) {
		this.sessions = sessions;
		this.settings = settings;
		this.gitOps = gitOps;
		this.journal = journal;
		this.bus = bus;
		this.mapper = mapper;
	}

	@Scheduled(fixedDelay = 30_000)
	void pollPrChecks() {
		if (!settings.prChecksEnabled()) {
			return;
		}
		Instant cutoff = Instant.now().minusSeconds(settings.prCheckPollIntervalSeconds());
		for (SessionEntity session : sessions.findAwaitingPrCheck(cutoff)) {
			try {
				checkOne(session);
			} catch (RuntimeException e) {
				log.warn("PR check failed for session {}: {}", session.id(), e.getMessage());
			}
		}
	}

	private void checkOne(SessionEntity session) {
		var result = gitOps.checkPrStatus(Path.of(session.worktreePath()), session.prUrl());
		String status = result.status().name();
		String previousStatus = session.prCheckStatus();
		sessions.updatePrCheck(session.id(), status, result.headSha(), Instant.now());
		if (!status.equals(previousStatus)) {
			ObjectNode payload = mapper.createObjectNode()
					.put("url", session.prUrl())
					.put("status", status)
					.put("previousStatus", previousStatus)
					.put("headSha", result.headSha());
			bus.publish(session.id(), journal.append(session.id(), "pr_status_changed", payload));
		}
	}
}
