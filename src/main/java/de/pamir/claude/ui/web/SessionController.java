package de.pamir.claude.ui.web;

import tools.jackson.databind.JsonNode;
import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.journal.TranscriptDigest;
import de.pamir.claude.ui.memory.ReflectionService;
import de.pamir.claude.ui.session.SessionEntity;
import de.pamir.claude.ui.session.SessionRepository;
import de.pamir.claude.ui.session.SessionService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

	public record CreateSessionRequest(String name, String branch, String baseBranch, String repoPath,
									   UUID templateId, JsonNode overrides, Map<String, String> kickoffValues,
									   Boolean syncBaseBranch) {
	}

	public record SessionSummary(UUID id, String name, String provider, String branch, String model,
								 String permissionMode, String state, String kind, BigDecimal costToDate) {
	}

	private final SessionService service;
	private final SessionRepository sessions;
	private final EventJournal journal;
	private final ReflectionService reflection;

	public SessionController(SessionService service, SessionRepository sessions, EventJournal journal,
							  ReflectionService reflection) {
		this.service = service;
		this.sessions = sessions;
		this.journal = journal;
		this.reflection = reflection;
	}

	@PostMapping
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
	public SessionEntity create(@RequestBody CreateSessionRequest request) {
		if (request.name() == null || request.branch() == null || request.baseBranch() == null) {
			throw new IllegalArgumentException("name, branch and baseBranch are required");
		}
		return service.create(request.name(), request.branch(), request.baseBranch(), request.repoPath(),
				request.templateId(), request.overrides(), request.kickoffValues(),
				Boolean.TRUE.equals(request.syncBaseBranch()));
	}

	@GetMapping
	public List<SessionSummary> list() {
		return sessions.findAll().stream()
				.map(s -> new SessionSummary(s.id(), s.name(), s.provider(), s.branch(), s.model(),
						s.permissionMode(), s.state().name(), s.kind(), journal.costToDate(s.id())))
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

	@GetMapping(value = "/{id}/export.md", produces = "text/markdown;charset=UTF-8")
	public ResponseEntity<String> export(@PathVariable UUID id) {
		SessionEntity session = sessions.get(id);
		String markdown = TranscriptDigest.renderMarkdown(session.name(), journal.readAfter(id, 0));
		String filename = session.name().replaceAll("[^a-zA-Z0-9._-]+", "-") + ".md";
		return ResponseEntity.ok()
				.contentType(MediaType.valueOf("text/markdown;charset=UTF-8"))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(filename).build().toString())
				.body(markdown);
	}

	@PostMapping("/{id}/resume")
	public SessionEntity resume(@PathVariable UUID id) {
		return service.resume(id);
	}

	public record DuplicateSessionRequest(String branch, String name, Boolean syncBaseBranch) {
	}

	@PostMapping("/{id}/duplicate")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
	public SessionEntity duplicate(@PathVariable UUID id, @RequestBody DuplicateSessionRequest request) {
		if (request.branch() == null || request.branch().isBlank()) {
			throw new IllegalArgumentException("branch is required");
		}
		return service.duplicate(id, request.branch(), request.name(), Boolean.TRUE.equals(request.syncBaseBranch()));
	}

	public record PatchSessionRequest(BigDecimal costBudgetUsd, String name, Boolean reflectionEnabled) {
	}

	@org.springframework.web.bind.annotation.PatchMapping("/{id}")
	public SessionEntity patch(@PathVariable UUID id, @RequestBody PatchSessionRequest request) {
		if (request.costBudgetUsd() != null) {
			service.updateCostBudget(id, request.costBudgetUsd());
		}
		if (request.name() != null && !request.name().isBlank()) {
			service.rename(id, request.name().strip());
		}
		if (request.reflectionEnabled() != null) {
			service.updateReflectionEnabled(id, request.reflectionEnabled());
		}
		return sessions.get(id);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> close(@PathVariable UUID id,
									  @RequestParam(defaultValue = "fail") String dirty,
									  @RequestParam(required = false) String commitMessage) {
		service.close(id, dirty, commitMessage);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/reflect")
	public ResponseEntity<Void> reflect(@PathVariable UUID id) {
		reflection.reflect(id);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}/queue/{pos}")
	public ResponseEntity<Void> deleteQueued(@PathVariable UUID id, @PathVariable long pos) {
		return service.deleteQueued(id, pos) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}
}
