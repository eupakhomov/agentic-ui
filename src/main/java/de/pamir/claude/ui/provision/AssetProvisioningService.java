package de.pamir.claude.ui.provision;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitCommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves skill/agent sources (dir | file | index | repo) and materializes them into
 * the worktree's provider asset layout (Claude: .claude/skills and .claude/agents).
 * Symlinks preferred, copy fallback. Never overwrites assets the repo itself ships.
 */
@Service
public class AssetProvisioningService {

	public record Warning(String message) {
	}

	private static final Logger log = LoggerFactory.getLogger(AssetProvisioningService.class);
	private static final int MAX_INDEX_DEPTH = 5;

	private final SettingsService settings;
	private final GitCommandRunner git;
	private final ObjectMapper mapper;

	public AssetProvisioningService(SettingsService settings, GitCommandRunner git, ObjectMapper mapper) {
		this.settings = settings;
		this.git = git;
		this.mapper = mapper;
	}

	/** Materializes skills and agents; returns human-readable warnings (collisions etc.). */
	public List<Warning> provision(Path worktree, JsonNode skillSources, JsonNode agentSources) {
		List<Warning> warnings = new ArrayList<>();
		materialize(resolveAll(skillSources, true, warnings, 0), worktree.resolve(".claude/skills"), true, warnings);
		materialize(resolveAll(agentSources, false, warnings, 0), worktree.resolve(".claude/agents"), false, warnings);
		return warnings;
	}

	// --- resolution: each source expands to skill dirs (dirs containing SKILL.md), agent .md
	// files, or plain files, depending on isSkill ---

	private List<Path> resolveAll(JsonNode sources, boolean isSkill, List<Warning> warnings, int depth) {
		List<Path> resolved = new ArrayList<>();
		if (sources == null || !sources.isArray()) {
			return resolved;
		}
		for (JsonNode source : sources) {
			String type = source.path("type").asText("dir");
			String ref = source.path("ref").asText();
			try {
				switch (type) {
					case "dir" -> resolved.addAll(expandDir(Path.of(ref), isSkill));
					case "file" -> resolved.add(Path.of(ref));
					case "index" -> resolved.addAll(expandIndex(Path.of(ref), isSkill, warnings, depth));
					case "repo" -> resolved.addAll(expandDir(cloneOrUpdate(ref, isSkill), isSkill));
					default -> warnings.add(new Warning("unknown asset source type: " + type));
				}
			} catch (Exception e) {
				warnings.add(new Warning("asset source " + type + ":" + ref + " failed: " + e.getMessage()));
			}
		}
		return resolved;
	}

	/**
	 * Skills: a dir with SKILL.md is one skill; otherwise each child dir containing SKILL.md
	 * counts. Agents: each top-level *.md file in the dir is one agent (agents are standalone
	 * files, never dirs — matches AssetScanService's own detection rule).
	 */
	private List<Path> expandDir(Path dir, boolean isSkill) throws IOException {
		if (!Files.isDirectory(dir)) {
			throw new IOException("not a directory: " + dir);
		}
		if (isSkill) {
			if (Files.exists(dir.resolve("SKILL.md"))) {
				return List.of(dir);
			}
			try (Stream<Path> children = Files.list(dir)) {
				return children.filter(c -> Files.exists(c.resolve("SKILL.md"))).sorted().toList();
			}
		}
		try (Stream<Path> children = Files.list(dir)) {
			return children.filter(c -> Files.isRegularFile(c) && c.getFileName().toString().endsWith(".md"))
					.sorted().toList();
		}
	}

	/** Index file: one source per line — a path, or `repo <url>`. Recursion is depth-capped. */
	private List<Path> expandIndex(Path indexFile, boolean isSkill, List<Warning> warnings, int depth) throws IOException {
		if (depth >= MAX_INDEX_DEPTH) {
			warnings.add(new Warning("asset index nesting too deep at " + indexFile));
			return List.of();
		}
		List<Path> resolved = new ArrayList<>();
		for (String raw : Files.readAllLines(indexFile)) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			var node = mapper.createObjectNode();
			if (line.startsWith("repo ")) {
				node.put("type", "repo").put("ref", line.substring(5).strip());
			} else if (line.endsWith(".txt") || line.endsWith(".index")) {
				node.put("type", "index").put("ref", line);
			} else if (line.endsWith("SKILL.md") || (!isSkill && line.endsWith(".md"))) {
				node.put("type", "file").put("ref", line);
			} else {
				node.put("type", "dir").put("ref", line);
			}
			resolved.addAll(resolveAll(mapper.createArrayNode().add(node), isSkill, warnings, depth + 1));
		}
		return resolved;
	}

	private Path cloneOrUpdate(String url, boolean isSkill) {
		Path cacheRoot = Path.of(settings.librarySkillsRoot(), ".repo-cache");
		String slug = HexFormat.of().toHexDigits(url.hashCode());
		Path target = cacheRoot.resolve(slug);
		try {
			Files.createDirectories(cacheRoot);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (Files.isDirectory(target.resolve(".git"))) {
			var pull = git.run(target, "pull", "--ff-only");
			if (!pull.ok()) {
				log.warn("asset repo {} update failed ({}); using cached copy", url, pull.stderr());
			}
		} else {
			git.runOrThrow(cacheRoot, "clone", "--depth", "1", url, target.toString());
		}
		// skills may live at the repo root or under skills/; agents under agents/
		String subdir = isSkill ? "skills" : "agents";
		return Files.isDirectory(target.resolve(subdir)) ? target.resolve(subdir) : target;
	}

	// --- materialization ---

	private void materialize(List<Path> sources, Path targetRoot, boolean asSkillDirs, List<Warning> warnings) {
		if (sources.isEmpty()) {
			return;
		}
		try {
			Files.createDirectories(targetRoot);
		} catch (IOException e) {
			warnings.add(new Warning("cannot create " + targetRoot + ": " + e.getMessage()));
			return;
		}
		for (Path source : sources) {
			String name = assetName(source, asSkillDirs);
			Path target = targetRoot.resolve(name);
			if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
				warnings.add(new Warning("asset '" + name + "' already exists in the worktree; skipped " + source));
				continue;
			}
			link(source, target, warnings);
		}
	}

	/** For a bare SKILL.md file, the parent dir name identifies the skill. */
	private String assetName(Path source, boolean asSkillDirs) {
		if (asSkillDirs && Files.isRegularFile(source)) {
			return source.getParent() != null ? source.getParent().getFileName().toString()
					: source.getFileName().toString();
		}
		return source.getFileName().toString();
	}

	private void link(Path source, Path target, List<Warning> warnings) {
		try {
			if (Files.isRegularFile(source) && source.getFileName().toString().equals("SKILL.md")) {
				Files.createDirectories(target);
				Files.createSymbolicLink(target.resolve("SKILL.md"), source.toAbsolutePath());
			} else {
				Files.createSymbolicLink(target, source.toAbsolutePath());
			}
		} catch (IOException | UnsupportedOperationException symlinkFailure) {
			try {
				copyRecursive(source, Files.isRegularFile(source)
						&& source.getFileName().toString().equals("SKILL.md")
						? target.resolve("SKILL.md") : target);
			} catch (IOException e) {
				warnings.add(new Warning("failed to materialize " + source + ": " + e.getMessage()));
			}
		}
	}

	private void copyRecursive(Path source, Path target) throws IOException {
		if (Files.isDirectory(source)) {
			try (Stream<Path> walk = Files.walk(source)) {
				for (Path p : walk.toList()) {
					Path dest = target.resolve(source.relativize(p).toString());
					if (Files.isDirectory(p)) {
						Files.createDirectories(dest);
					} else {
						Files.createDirectories(dest.getParent());
						Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		} else {
			Files.createDirectories(target.getParent());
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
