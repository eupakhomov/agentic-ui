package de.pamir.agentic.ui.web;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.git.GitOpsService;
import de.pamir.agentic.ui.git.GitWorktreeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api")
public class MetaController {

	private final AppProperties props;
	private final SettingsService settings;
	private final GitWorktreeService worktrees;
	private final GitOpsService gitOps;

	public MetaController(AppProperties props, SettingsService settings, GitWorktreeService worktrees,
						   GitOpsService gitOps) {
		this.props = props;
		this.settings = settings;
		this.worktrees = worktrees;
		this.gitOps = gitOps;
	}

	public record ServiceInfo(String name, String path, String repoPath, boolean monorepo) {
	}

	public record ServicesResponse(String ecosystemRoot, String defaultRepoPath, List<ServiceInfo> services) {
	}

	/**
	 * Every service (a git repo, or a monorepo's packages) under the ecosystem root — the
	 * per-session picker's choices (docs/plan/phase-11-monorepo.md Step 3). {@code monorepo} is
	 * {@code false} and {@code repoPath == path} for a plain polyrepo service, unchanged from
	 * before this phase.
	 */
	@GetMapping("/repo/services")
	public ServicesResponse services() {
		List<String> globs = monorepoGlobs();
		boolean monorepoDetectionEnabled = settings.current().monorepoDetectionEnabled();
		List<ServiceInfo> services = new ArrayList<>();
		String ecosystemRoot = settings.current().ecosystemRoot();
		if (!ecosystemRoot.isBlank()) {
			worktrees.findServices(Path.of(ecosystemRoot), globs, monorepoDetectionEnabled)
					.forEach(svc -> services.add(toServiceInfo(svc)));
		}
		Path configured = Path.of(props.repoPath());
		// The configured default repo goes through the same detection as everything else — a
		// monorepo default repo lists its packages too, not just the repo as one service — but only
		// as a fallback: if the ecosystem scan above already surfaced it (its repoPath, not its own
		// path, since a monorepo's packages never equal the repo root), don't duplicate it.
		if (services.stream().noneMatch(s -> s.repoPath().equals(configured.toString()))
				&& Files.exists(configured.resolve(".git"))) {
			List<ServiceInfo> defaults = worktrees.findServices(configured, globs, monorepoDetectionEnabled).stream()
					.map(this::toServiceInfo).toList();
			services.addAll(0, defaults);
		}
		return new ServicesResponse(ecosystemRoot, props.repoPath(), services);
	}

	private ServiceInfo toServiceInfo(GitWorktreeService.ServiceInfo svc) {
		return new ServiceInfo(svc.name(), svc.servicePath(), svc.repoPath(), !svc.servicePath().equals(svc.repoPath()));
	}

	private List<String> monorepoGlobs() {
		return Arrays.stream(settings.current().monorepoServiceGlobs().split(","))
				.map(String::strip).filter(g -> !g.isEmpty()).toList();
	}

	/**
	 * {@code remote=true} (the review create-dialog's branch-fallback picker only — proposal 13's
	 * safer alternative, so the ordinary dev-flow dialogs' branch list is untouched) also appends
	 * remote-tracking branches not already listed locally, since a branch under review usually
	 * exists only on the remote.
	 */
	@GetMapping("/repo/branches")
	public List<String> branches(@RequestParam(required = false) String repo,
								  @RequestParam(defaultValue = "false") boolean remote) {
		Path path = Path.of(repo == null || repo.isBlank() ? props.repoPath() : repo);
		if (!Files.exists(path.resolve(".git"))) {
			throw new IllegalArgumentException("not a git repository: " + path);
		}
		List<String> locals = worktrees.localBranches(path);
		if (!remote) {
			return locals;
		}
		List<String> merged = new ArrayList<>(locals);
		merged.addAll(worktrees.remoteBranches(path));
		return merged;
	}

	/** Open PRs for the review create-dialog's PR picker (docs/plan/phase-15-review-sessions.md proposal 1/11). */
	@GetMapping("/repo/prs")
	public List<GitOpsService.PrInfo> prs(@RequestParam(required = false) String repo) {
		Path path = Path.of(repo == null || repo.isBlank() ? props.repoPath() : repo);
		if (!Files.exists(path.resolve(".git"))) {
			throw new IllegalArgumentException("not a git repository: " + path);
		}
		return gitOps.listOpenPrs(path);
	}
}
