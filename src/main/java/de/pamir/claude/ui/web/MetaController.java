package de.pamir.claude.ui.web;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitWorktreeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
public class MetaController {

	private final AppProperties props;
	private final SettingsService settings;
	private final GitWorktreeService worktrees;

	public MetaController(AppProperties props, SettingsService settings, GitWorktreeService worktrees) {
		this.props = props;
		this.settings = settings;
		this.worktrees = worktrees;
	}

	public record ServiceInfo(String name, String path) {
	}

	public record ServicesResponse(String ecosystemRoot, String defaultRepoPath, List<ServiceInfo> services) {
	}

	/** Git repos directly under the ecosystem root — the per-session service choices. */
	@GetMapping("/repo/services")
	public ServicesResponse services() {
		List<ServiceInfo> services = new ArrayList<>();
		String ecosystemRoot = settings.ecosystemRoot();
		if (!ecosystemRoot.isBlank()) {
			worktrees.findRepos(Path.of(ecosystemRoot))
					.forEach(r -> services.add(new ServiceInfo(r.name(), r.path())));
		}
		Path configured = Path.of(props.repoPath());
		if (services.stream().noneMatch(s -> s.path().equals(configured.toString()))
				&& Files.exists(configured.resolve(".git"))) {
			services.add(0, new ServiceInfo(configured.getFileName().toString(), configured.toString()));
		}
		return new ServicesResponse(ecosystemRoot, props.repoPath(), services);
	}

	@GetMapping("/repo/branches")
	public List<String> branches(@org.springframework.web.bind.annotation.RequestParam(required = false) String repo) {
		Path path = Path.of(repo == null || repo.isBlank() ? props.repoPath() : repo);
		if (!Files.exists(path.resolve(".git"))) {
			throw new IllegalArgumentException("not a git repository: " + path);
		}
		return worktrees.localBranches(path);
	}
}
