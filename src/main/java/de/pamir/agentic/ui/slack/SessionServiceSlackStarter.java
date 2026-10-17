package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.git.GitOpsService;
import de.pamir.agentic.ui.git.GitOpsService.PrDetails;
import de.pamir.agentic.ui.git.GitOpsService.PrRef;
import de.pamir.agentic.ui.git.GitWorktreeService;
import de.pamir.agentic.ui.git.ServiceCatalog;
import de.pamir.agentic.ui.git.ServiceCatalog.Service;
import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionService;
import de.pamir.agentic.ui.session.TemplateRepository;
import de.pamir.agentic.ui.session.TemplateRepository.TemplateEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link SlackSessionStarter} on the real services. A session started here is created exactly like
 * the dashboard's quick start: the settings of the most recent session are inherited
 * ({@link SessionService#lastSessionConfig()} — model, permission mode, allowed tools, MCP, skills),
 * the base branch is synced, and a review session is a detached checkout of the PR's branch. It
 * differs in two ways: Slack mirroring is switched on (that is where it was asked for), and the
 * prompt is sent at once instead of being left as an unsent draft. With a template the template wins over
 * the inherited settings, exactly as in the dashboard (see {@link #buildOverrides}).
 */
@Component
@ConditionalOnProperty(prefix = "agentic-ui.slack", name = "enabled", havingValue = "true")
class SessionServiceSlackStarter implements SlackSessionStarter {

	private final ServiceCatalog catalog;
	private final GitOpsService gitOps;
	private final GitWorktreeService worktrees;
	private final SessionService sessions;
	private final TemplateRepository templates;
	private final ObjectMapper mapper;

	SessionServiceSlackStarter(ServiceCatalog catalog, GitOpsService gitOps, GitWorktreeService worktrees,
							   SessionService sessions, TemplateRepository templates, ObjectMapper mapper) {
		this.catalog = catalog;
		this.gitOps = gitOps;
		this.worktrees = worktrees;
		this.sessions = sessions;
		this.templates = templates;
		this.mapper = mapper;
	}

	@Override
	public List<Service> services() {
		return catalog.services();
	}

	@Override
	public List<TemplateInfo> templates() {
		return templates.findAll().stream()
				.map(t -> new TemplateInfo(t.id(), t.name(), t.description(),
						t.config() != null && t.config().hasNonNull("baseBranch") ? t.config().get("baseBranch").asText() : null))
				.toList();
	}

	@Override
	public Optional<String> originSlug(String repoPath) {
		return gitOps.originSlug(Path.of(repoPath));
	}

	@Override
	public PrDetails viewPr(String anyRepoPath, PrRef ref) {
		return gitOps.viewPr(Path.of(anyRepoPath), ref);
	}

	@Override
	public String defaultBaseBranch(String repoPath) {
		List<String> branches = worktrees.localBranches(Path.of(repoPath));
		if (branches.contains("main")) {
			return "main";
		}
		if (branches.contains("master")) {
			return "master";
		}
		return branches.isEmpty() ? "main" : branches.get(0);
	}

	@Override
	public Started start(StartRequest request) {
		JsonNode templateConfig = request.templateId() == null ? null : templates.get(request.templateId()).config();
		ObjectNode overrides = buildOverrides(sessions.lastSessionConfig(), templateConfig, mapper);
		SessionService.CreateOptions options = new SessionService.CreateOptions(request.name(), request.branch(),
				request.baseBranch(), request.repoPath(), request.templateId(), overrides, Map.of(), true)
				.withServicePath(request.servicePath())
				.withSessionType(request.sessionType())
				.withPrUrl(request.prUrl());
		SessionEntity created = sessions.create(options);
		// who asked, for the session's own journal (and a note in its Slack thread)
		sessions.recordExternalInput(created.id(), mapper.createObjectNode().put("source", "slack")
				.put("slackUser", request.slackUser()).put("kind", "start").put("tool", request.sessionType()));
		sessions.sendUserMessage(created.id(), request.prompt());
		return new Started(created.id(), created.name(), created.model(), created.permissionMode());
	}

	/**
	 * The overrides a Slack-started session is created with. Without a template: what the dashboard's quick
	 * start would inherit ({@code last}, the most recent session's settings). With one: exactly the
	 * dashboard quick start's rule — the template wins, so every key it sets is dropped from the inherited
	 * ones (and the inherited skill/agent lists, so the template's own linked assets are merged server-side
	 * instead). Always: Slack mirroring on (that is where it was asked for), and no kickoff prompt — the first
	 * message is what was typed in Slack, and a template's own kickoff must not also fire.
	 */
	static ObjectNode buildOverrides(JsonNode last, JsonNode templateConfig, ObjectMapper mapper) {
		ObjectNode overrides = last instanceof ObjectNode obj ? obj.deepCopy() : mapper.createObjectNode();
		if (templateConfig instanceof ObjectNode template) {
			template.propertyNames().forEach(overrides::remove);
			overrides.remove("skillSources");
			overrides.remove("agentSources");
		}
		overrides.put("slackEnabled", true);
		overrides.put("kickoffPrompt", "");
		return overrides;
	}
}
