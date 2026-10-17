package de.pamir.agentic.ui.git;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.SettingsService;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Every service a session can be started on: those under the ecosystem root plus, as a fallback, the
 * configured default repo. The one place this list is computed, shared by the dashboard's service
 * picker ({@code GET /api/repo/services}) and by Slack's "start a session" commands, so both always
 * agree on what a "service" is (docs/plan/phase-11-monorepo.md Step 3).
 */
@Component
public class ServiceCatalog {

	/** {@code monorepo} is true for a package inside a repo that holds several; its {@code repoPath} is the shared git root. */
	public record Service(String name, String path, String repoPath, boolean monorepo) {
	}

	private final AppProperties props;
	private final SettingsService settings;
	private final GitWorktreeService worktrees;

	public ServiceCatalog(AppProperties props, SettingsService settings, GitWorktreeService worktrees) {
		this.props = props;
		this.settings = settings;
		this.worktrees = worktrees;
	}

	public String ecosystemRoot() {
		return settings.current().ecosystemRoot();
	}

	public String defaultRepoPath() {
		return props.repoPath();
	}

	public List<Service> services() {
		List<String> globs = monorepoGlobs();
		boolean monorepoDetectionEnabled = settings.current().monorepoDetectionEnabled();
		List<Service> services = new ArrayList<>();
		String ecosystemRoot = settings.current().ecosystemRoot();
		if (!ecosystemRoot.isBlank()) {
			worktrees.findServices(Path.of(ecosystemRoot), globs, monorepoDetectionEnabled)
					.forEach(svc -> services.add(toService(svc)));
		}
		Path configured = Path.of(props.repoPath());
		// The configured default repo goes through the same detection as everything else — a
		// monorepo default repo lists its packages too, not just the repo as one service — but only
		// as a fallback: if the ecosystem scan above already surfaced it (its repoPath, not its own
		// path, since a monorepo's packages never equal the repo root), don't duplicate it.
		if (services.stream().noneMatch(s -> s.repoPath().equals(configured.toString()))
				&& Files.exists(configured.resolve(".git"))) {
			List<Service> defaults = worktrees.findServices(configured, globs, monorepoDetectionEnabled).stream()
					.map(this::toService).toList();
			services.addAll(0, defaults);
		}
		return services;
	}

	private Service toService(GitWorktreeService.ServiceInfo svc) {
		return new Service(svc.name(), svc.servicePath(), svc.repoPath(), !svc.servicePath().equals(svc.repoPath()));
	}

	private List<String> monorepoGlobs() {
		return Arrays.stream(settings.current().monorepoServiceGlobs().split(","))
				.map(String::strip).filter(g -> !g.isEmpty()).toList();
	}
}
