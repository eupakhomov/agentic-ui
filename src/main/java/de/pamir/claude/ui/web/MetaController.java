package de.pamir.claude.ui.web;

import de.pamir.claude.ui.config.AppProperties;
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
	private final GitWorktreeService worktrees;

	public MetaController(AppProperties props, GitWorktreeService worktrees) {
		this.props = props;
		this.worktrees = worktrees;
	}

	@GetMapping("/repo/branches")
	public List<String> branches() {
		return worktrees.localBranches(Path.of(props.repoPath()));
	}

	/** Skills found in the configured skills-root library (dirs containing SKILL.md). */
	@GetMapping("/skills")
	public List<SkillInfo> skills() {
		Path root = Path.of(props.skillsRoot());
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
