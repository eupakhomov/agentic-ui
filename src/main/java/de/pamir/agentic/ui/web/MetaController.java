package de.pamir.agentic.ui.web;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.git.GitOpsService;
import de.pamir.agentic.ui.git.ServiceCatalog;
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
	private final ServiceCatalog catalog;

	public MetaController(AppProperties props, SettingsService settings, GitWorktreeService worktrees,
						   GitOpsService gitOps) {
		this.props = props;
		this.settings = settings;
		this.worktrees = worktrees;
		this.gitOps = gitOps;
		this.catalog = new ServiceCatalog(props, settings, worktrees);
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
		List<ServiceInfo> services = catalog.services().stream()
				.map(svc -> new ServiceInfo(svc.name(), svc.path(), svc.repoPath(), svc.monorepo())).toList();
		return new ServicesResponse(catalog.ecosystemRoot(), catalog.defaultRepoPath(), services);
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
