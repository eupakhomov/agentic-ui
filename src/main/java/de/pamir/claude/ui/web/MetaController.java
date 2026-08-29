package de.pamir.claude.ui.web;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitWorktreeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
public class MetaController {

	public record SkillInfo(String name, String description, String path) {
	}

	private static final Pattern FRONTMATTER_FIELD = Pattern.compile("^(name|description):\\s*(.+)$");

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
		Path root = Path.of(ecosystemRoot.isBlank() ? "." : ecosystemRoot);
		if (!ecosystemRoot.isBlank() && Files.isDirectory(root)) {
			try (Stream<Path> children = Files.list(root)) {
				children.filter(c -> Files.exists(c.resolve(".git"))).sorted()
						.forEach(c -> services.add(new ServiceInfo(c.getFileName().toString(), c.toString())));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
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

	/** Skills found in the managed skills root (dirs containing SKILL.md) — a persisted setting. */
	@GetMapping("/skills")
	public List<SkillInfo> skills() {
		Path root = Path.of(settings.librarySkillsRoot());
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		try (Stream<Path> children = Files.list(root)) {
			return children
					.filter(dir -> Files.exists(dir.resolve("SKILL.md")))
					.sorted()
					.map(this::describe)
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private SkillInfo describe(Path skillDir) {
		String name = skillDir.getFileName().toString();
		String description = "";
		try {
			for (String line : Files.readAllLines(skillDir.resolve("SKILL.md"))) {
				Matcher matcher = FRONTMATTER_FIELD.matcher(line.strip());
				if (matcher.matches()) {
					if (matcher.group(1).equals("name")) {
						name = matcher.group(2).strip();
					} else {
						description = matcher.group(2).strip();
					}
				}
				if (line.strip().equals("---") && !description.isEmpty()) {
					break;
				}
			}
		} catch (IOException ignored) {
			// listing survives an unreadable skill
		}
		return new SkillInfo(name, description, skillDir.toString());
	}
}
