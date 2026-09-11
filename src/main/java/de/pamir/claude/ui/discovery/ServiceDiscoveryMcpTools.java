package de.pamir.claude.ui.discovery;

import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitWorktreeService;
import de.pamir.claude.ui.library.EmbeddingClient;
import de.pamir.claude.ui.session.SessionEntity;
import de.pamir.claude.ui.session.SessionRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The two service-discovery tools (docs/plan/phase-8-service-discovery.md), living on the same
 * shared in-process MCP server as memory/orchestration (decision 6) — the server's attachment
 * condition is {@code memory.enabled || serviceDiscoveryEnabled()}, so both tools self-gate here
 * independently on their own setting rather than relying on non-attachment to disable them.
 * Both restrict results to the calling session's own visible ecosystem (decision 7), the same
 * {@code worktrees.findServices(session.ecosystemPath())} scoping {@code list_services} already uses.
 */
@Component
public class ServiceDiscoveryMcpTools {

	public record DescriptionResult(String name, String description, List<String> tags, String discoveredAt) {
	}

	public record FindResult(String servicePath, String name, String description, double score) {
	}

	public record ServiceSummary(String servicePath, String name, String description, List<String> tags) {
	}

	private final SettingsService settings;
	private final SessionRepository sessions;
	private final GitWorktreeService worktrees;
	private final ServiceProfileRepository profiles;
	private final EmbeddingClient embeddings;

	public ServiceDiscoveryMcpTools(SettingsService settings, SessionRepository sessions,
									 GitWorktreeService worktrees, ServiceProfileRepository profiles,
									 EmbeddingClient embeddings) {
		this.settings = settings;
		this.sessions = sessions;
		this.worktrees = worktrees;
		this.profiles = profiles;
		this.embeddings = embeddings;
	}

	@McpTool(name = "service_description",
			annotations = @McpTool.McpAnnotations(title = "Service description", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = "Get the auto-generated description of another service (git repo) in this "
					+ "session's ecosystem, by its path (from list_services or find_service). Errors if the "
					+ "service hasn't been discovered yet or isn't visible to this session.")
	public DescriptionResult serviceDescription(
			@McpToolParam(required = true, description = "Your session id, given in your system prompt")
			String sessionId,
			@McpToolParam(required = true, description = "Absolute path to the service's repo, from list_services")
			String servicePath) {
		requireEnabled();
		if (!visiblePaths(sessionId).contains(servicePath)) {
			throw new IllegalArgumentException("not a service visible to this session: " + servicePath);
		}
		var profile = profiles.findByServicePath(servicePath)
				.orElseThrow(() -> new NoSuchElementException("service not discovered yet: " + servicePath));
		return new DescriptionResult(profile.name(), profile.description(), profile.tags(),
				profile.discoveredAt().toString());
	}

	@McpTool(name = "find_service",
			annotations = @McpTool.McpAnnotations(title = "Find service", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = "Find which service (git repo) in this session's ecosystem implements some "
					+ "functionality, by natural-language query. Returns ranked service paths + descriptions "
					+ "— call service_description or spawn_child_session there next.")
	public List<FindResult> findService(
			@McpToolParam(required = true, description = "Your session id, given in your system prompt")
			String sessionId,
			@McpToolParam(required = true, description = "What functionality you're looking for, in plain language")
			String query) {
		requireEnabled();
		List<String> visible = visiblePaths(sessionId);
		float[] embedding = embeddings.configured() ? embeddings.embed(query, true) : null;
		return profiles.hybridSearch(query, embedding, visible, 10).stream()
				.map(hit -> new FindResult(hit.profile().servicePath(), hit.profile().name(),
						hit.profile().description(), hit.score()))
				.toList();
	}

	@McpTool(name = "list_discovered_services",
			annotations = @McpTool.McpAnnotations(title = "List discovered services", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = "List every service in this session's ecosystem that has an auto-generated "
					+ "description, each with its path and short description — an overview when you don't "
					+ "have a specific functionality to search for yet (see find_service for that). Services "
					+ "not yet discovered are omitted.")
	public List<ServiceSummary> listDiscoveredServices(
			@McpToolParam(required = true, description = "Your session id, given in your system prompt")
			String sessionId) {
		requireEnabled();
		return profiles.findVisible(visiblePaths(sessionId)).stream()
				.map(p -> new ServiceSummary(p.servicePath(), p.name(), p.description(), p.tags()))
				.toList();
	}

	private void requireEnabled() {
		if (!settings.current().serviceDiscoveryEnabled()) {
			throw new IllegalStateException("service discovery is disabled");
		}
	}

	private List<String> visiblePaths(String sessionId) {
		SessionEntity session = sessionOf(sessionId);
		if (session.ecosystemPath() == null || session.ecosystemPath().isBlank()) {
			return List.of();
		}
		return worktrees.findServices(Path.of(session.ecosystemPath()), monorepoGlobs()).stream()
				.map(GitWorktreeService.ServiceInfo::servicePath).toList();
	}

	private List<String> monorepoGlobs() {
		return Arrays.stream(settings.current().monorepoServiceGlobs().split(","))
				.map(String::strip).filter(g -> !g.isEmpty()).toList();
	}

	private SessionEntity sessionOf(String sessionId) {
		UUID id;
		try {
			id = UUID.fromString(sessionId);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("sessionId is not a valid session id: " + sessionId);
		}
		try {
			return sessions.get(id);
		} catch (NoSuchElementException e) {
			throw new NoSuchElementException("no session found for sessionId: " + sessionId);
		}
	}
}
