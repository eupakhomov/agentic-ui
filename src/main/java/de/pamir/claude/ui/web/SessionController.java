package de.pamir.claude.ui.web;

import tools.jackson.databind.JsonNode;
import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.session.SessionEntity;
import de.pamir.claude.ui.session.SessionRepository;
import de.pamir.claude.ui.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

	public record CreateSessionRequest(String name, String branch, String baseBranch, UUID templateId,
									   JsonNode overrides, Map<String, String> kickoffValues) {
	}

	public record SessionSummary(UUID id, String name, String provider, String branch, String model,
								 String permissionMode, String state, BigDecimal costToDate) {
	}

	private final SessionService service;
	private final SessionRepository sessions;
	private final EventJournal journal;

	public SessionController(SessionService service, SessionRepository sessions, EventJournal journal) {
		this.service = service;
		this.sessions = sessions;
		this.journal = journal;
	}

	@PostMapping
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
	public SessionEntity create(@RequestBody CreateSessionRequest request) {
		if (request.name() == null || request.branch() == null || request.baseBranch() == null) {
			throw new IllegalArgumentException("name, branch and baseBranch are required");
		}
		return service.create(request.name(), request.branch(), request.baseBranch(),
				request.templateId(), request.overrides(), request.kickoffValues());
	}

	@GetMapping
	public List<SessionSummary> list() {
		return sessions.findAll().stream()
				.map(s -> new SessionSummary(s.id(), s.name(), s.provider(), s.branch(), s.model(),
						s.permissionMode(), s.state().name(), journal.costToDate(s.id())))
				.toList();
	}

	@GetMapping("/{id}")
	public Map<String, Object> detail(@PathVariable UUID id) {
		SessionEntity session = sessions.get(id);
		return Map.of(
				"session", session,
				"queued", sessions.queued(id),
				"lastSeq", journal.lastSeq(id),
				"costToDate", journal.costToDate(id));
	}

	@GetMapping("/{id}/events")
	public List<EventJournal.JournalEvent> events(@PathVariable UUID id,
												  @RequestParam(defaultValue = "0") long afterSeq) {
		sessions.get(id);
		return journal.readAfter(id, afterSeq);
	}

	@PostMapping("/{id}/resume")
	public SessionEntity resume(@PathVariable UUID id) {
		return service.resume(id);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> close(@PathVariable UUID id,
									  @RequestParam(defaultValue = "fail") String dirty,
									  @RequestParam(required = false) String commitMessage) {
		service.close(id, dirty, commitMessage);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}/queue/{pos}")
	public ResponseEntity<Void> deleteQueued(@PathVariable UUID id, @PathVariable long pos) {
		return service.deleteQueued(id, pos) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}
}
