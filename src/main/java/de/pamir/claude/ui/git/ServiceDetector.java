package de.pamir.claude.ui.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Pure, git-free workspace-manifest detector: given a repo root, finds the folders that are its
 * "services" (monorepo packages). No Spring, no shelling out to git — {@link GitWorktreeService}
 * wraps this with the repo/ecosystem awareness. See docs/plan/phase-11-monorepo.md Step 1's
 * detection table for the exact per-manifest rules this implements, in order (first match wins):
 * npm/yarn {@code package.json#workspaces}, {@code pnpm-workspace.yaml}, Maven {@code <modules>},
 * Gradle {@code settings.gradle(.kts)} {@code include(...)}, Cargo {@code [workspace] members},
 * {@code go.work} {@code use} lines/blocks, else a glob-pattern fallback (filtered to folders that
 * themselves contain a manifest) — a repo with none of these is one service (empty result; the
 * caller treats that as "the repo root itself").
 */
public final class ServiceDetector {

	private static final Logger log = LoggerFactory.getLogger(ServiceDetector.class);
	private static final JsonMapper MAPPER = new JsonMapper();
	private static final Set<String> IGNORED_DIR_NAMES = Set.of("node_modules", "target", "build", "dist");
	private static final List<String> FALLBACK_MARKER_FILES =
			List.of("package.json", "pom.xml", "build.gradle", "build.gradle.kts", "pyproject.toml", "go.mod",
					"Cargo.toml", "Dockerfile");

	private ServiceDetector() {
	}

	/** Absolute, sorted service folders under {@code repoRoot}; empty = "one service = repoRoot". */
	public static List<Path> detect(Path repoRoot, List<String> fallbackGlobs) {
		List<Path> viaManifest = tryManifests(repoRoot);
		if (viaManifest != null) {
			return viaManifest;
		}
		List<Path> candidates = resolveGlobs(repoRoot, fallbackGlobs);
		return candidates.stream().filter(ServiceDetector::hasFallbackMarker).sorted().toList();
	}

	/** First manifest row (in order) that resolves to at least one folder; {@code null} = none did. */
	private static List<Path> tryManifests(Path repoRoot) {
		for (var attempt : List.<java.util.function.Function<Path, List<Path>>>of(
				ServiceDetector::tryNpmYarn, ServiceDetector::tryPnpm, ServiceDetector::tryMaven,
				ServiceDetector::tryGradle, ServiceDetector::tryCargo, ServiceDetector::tryGoWork)) {
			List<Path> result = attempt.apply(repoRoot);
			if (result != null && !result.isEmpty()) {
				return result;
			}
		}
		return null;
	}

	// --- npm / yarn: package.json#workspaces ---------------------------------------------------

	private static List<Path> tryNpmYarn(Path repoRoot) {
		Path pkg = repoRoot.resolve("package.json");
		if (!Files.isRegularFile(pkg)) {
			return null;
		}
		try {
			JsonNode root = MAPPER.readTree(Files.readString(pkg));
			JsonNode ws = root.get("workspaces");
			if (ws == null) {
				return List.of();
			}
			List<String> patterns = new ArrayList<>();
			if (ws.isArray()) {
				ws.forEach(n -> patterns.add(n.asString()));
			} else if (ws.isObject() && ws.path("packages").isArray()) {
				ws.get("packages").forEach(n -> patterns.add(n.asString()));
			} else {
				return List.of();
			}
			return resolveGlobs(repoRoot, patterns);
		} catch (IOException | RuntimeException e) {
			log.debug("could not parse {}: {}", pkg, e.getMessage());
			return List.of();
		}
	}

	// --- pnpm-workspace.yaml ---------------------------------------------------------------------

	private static List<Path> tryPnpm(Path repoRoot) {
		Path yaml = repoRoot.resolve("pnpm-workspace.yaml");
		if (!Files.isRegularFile(yaml)) {
			return null;
		}
		try {
			List<String> lines = Files.readAllLines(yaml);
			List<String> patterns = new ArrayList<>();
			boolean inPackages = false;
			for (String raw : lines) {
				String line = stripYamlComment(raw);
				if (line.isBlank()) {
					continue;
				}
				if (!inPackages) {
					if (line.strip().equals("packages:")) {
						inPackages = true;
					}
					continue;
				}
				String trimmed = line.strip();
				if (!trimmed.startsWith("-")) {
					break; // end of the packages list
				}
				String item = trimmed.substring(1).strip();
				item = unquote(item);
				if (!item.isEmpty()) {
					patterns.add(item);
				}
			}
			return resolveGlobs(repoRoot, patterns);
		} catch (IOException e) {
			log.debug("could not parse {}: {}", yaml, e.getMessage());
			return List.of();
		}
	}

	private static String stripYamlComment(String line) {
		int hash = line.indexOf('#');
		return hash < 0 ? line : line.substring(0, hash);
	}

	// --- Maven pom.xml <modules> -------------------------------------------------------------------

	private static final Pattern MODULE_TAG = Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");

	private static List<Path> tryMaven(Path repoRoot) {
		Path pom = repoRoot.resolve("pom.xml");
		if (!Files.isRegularFile(pom)) {
			return null;
		}
		try {
			String content = Files.readString(pom);
			List<String> patterns = new ArrayList<>();
			Matcher m = MODULE_TAG.matcher(content);
			while (m.find()) {
				String module = m.group(1).strip();
				if (module.endsWith("pom.xml")) {
					module = module.substring(0, module.length() - "pom.xml".length());
					module = module.isEmpty() ? "." : module;
					if (module.endsWith("/")) {
						module = module.substring(0, module.length() - 1);
					}
				}
				if (!module.isEmpty()) {
					patterns.add(module);
				}
			}
			return resolveGlobs(repoRoot, patterns);
		} catch (IOException e) {
			log.debug("could not parse {}: {}", pom, e.getMessage());
			return List.of();
		}
	}

	// --- Gradle settings.gradle(.kts) include(...) -------------------------------------------------

	private static final Pattern INCLUDE_CALL = Pattern.compile("include\\s*\\(([^)]*)\\)|include\\s+([^\\n]+)");
	private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]+)['\"]");
	private static final Pattern PROJECT_DIR_OVERRIDE =
			Pattern.compile("project\\(['\"](:[\\w:.\\-]+)['\"]\\)\\.projectDir\\s*=\\s*file\\(['\"]([^'\"]+)['\"]\\)");

	private static List<Path> tryGradle(Path repoRoot) {
		Path kts = repoRoot.resolve("settings.gradle.kts");
		Path groovy = repoRoot.resolve("settings.gradle");
		Path settings = Files.isRegularFile(kts) ? kts : (Files.isRegularFile(groovy) ? groovy : null);
		if (settings == null) {
			return null;
		}
		try {
			String content = Files.readString(settings);
			Map<String, String> overrides = new java.util.HashMap<>();
			Matcher overrideMatcher = PROJECT_DIR_OVERRIDE.matcher(content);
			while (overrideMatcher.find()) {
				overrides.put(overrideMatcher.group(1), overrideMatcher.group(2));
			}
			List<String> patterns = new ArrayList<>();
			Matcher m = INCLUDE_CALL.matcher(content);
			while (m.find()) {
				String args = m.group(1) != null ? m.group(1) : m.group(2);
				Matcher tokens = QUOTED.matcher(args);
				while (tokens.find()) {
					String gradlePath = tokens.group(1);
					if (!gradlePath.startsWith(":")) {
						continue;
					}
					String override = overrides.get(gradlePath);
					if (override != null) {
						patterns.add(override);
					} else {
						patterns.add(gradlePath.substring(1).replace(':', '/'));
					}
				}
			}
			return resolveGlobs(repoRoot, patterns);
		} catch (IOException e) {
			log.debug("could not parse {}: {}", settings, e.getMessage());
			return List.of();
		}
	}

	// --- Cargo.toml [workspace] members / exclude ---------------------------------------------------

	private static List<Path> tryCargo(Path repoRoot) {
		Path cargo = repoRoot.resolve("Cargo.toml");
		if (!Files.isRegularFile(cargo)) {
			return null;
		}
		try {
			String content = Files.readString(cargo);
			String workspaceSection = tomlSection(content, "workspace");
			if (workspaceSection == null) {
				return List.of();
			}
			List<String> members = tomlBracketList(workspaceSection, "members");
			List<String> exclude = tomlBracketList(workspaceSection, "exclude");
			List<String> patterns = new ArrayList<>(members);
			exclude.forEach(e -> patterns.add("!" + e));
			return resolveGlobs(repoRoot, patterns);
		} catch (IOException e) {
			log.debug("could not parse {}: {}", cargo, e.getMessage());
			return List.of();
		}
	}

	/** Content of a {@code [name]} TOML section, up to the next {@code [...]} header or EOF. */
	private static String tomlSection(String content, String name) {
		Pattern header = Pattern.compile("(?m)^\\[" + Pattern.quote(name) + "]\\s*$");
		Matcher m = header.matcher(content);
		if (!m.find()) {
			return null;
		}
		int start = m.end();
		Matcher next = Pattern.compile("(?m)^\\[").matcher(content);
		int end = content.length();
		if (next.find(start)) {
			end = next.start();
		}
		return content.substring(start, end);
	}

	/** {@code key = [ "a", "b" ]}, possibly spanning multiple lines. */
	private static List<String> tomlBracketList(String section, String key) {
		Pattern keyPattern = Pattern.compile(Pattern.quote(key) + "\\s*=\\s*\\[");
		Matcher m = keyPattern.matcher(section);
		if (!m.find()) {
			return List.of();
		}
		int close = section.indexOf(']', m.end());
		if (close < 0) {
			return List.of();
		}
		String body = section.substring(m.end(), close);
		List<String> values = new ArrayList<>();
		Matcher tokens = QUOTED.matcher(body);
		while (tokens.find()) {
			values.add(tokens.group(1));
		}
		return values;
	}

	// --- go.work use lines/blocks --------------------------------------------------------------------

	private static List<Path> tryGoWork(Path repoRoot) {
		Path goWork = repoRoot.resolve("go.work");
		if (!Files.isRegularFile(goWork)) {
			return null;
		}
		try {
			List<String> patterns = new ArrayList<>();
			boolean inBlock = false;
			for (String raw : Files.readAllLines(goWork)) {
				String line = raw.strip();
				if (line.startsWith("//") || line.isEmpty()) {
					continue;
				}
				if (inBlock) {
					if (line.equals(")")) {
						inBlock = false;
					} else {
						patterns.add(normalizeGoUsePath(line));
					}
					continue;
				}
				if (line.equals("use (")) {
					inBlock = true;
				} else if (line.startsWith("use ")) {
					patterns.add(normalizeGoUsePath(line.substring("use ".length()).strip()));
				}
			}
			return resolveGlobs(repoRoot, patterns);
		} catch (IOException e) {
			log.debug("could not parse {}: {}", goWork, e.getMessage());
			return List.of();
		}
	}

	private static String normalizeGoUsePath(String p) {
		String cleaned = p.strip();
		if (cleaned.startsWith("./")) {
			cleaned = cleaned.substring(2);
		}
		return cleaned;
	}

	// --- glob resolution shared by every row --------------------------------------------------------

	private static String unquote(String s) {
		if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
			return s.substring(1, s.length() - 1);
		}
		return s;
	}

	/**
	 * A pattern is a "/"-separated relative path whose segments are literal or "*" (one directory
	 * level; "**" is treated the same as "*"); a leading "!" removes matches of the rest of the
	 * pattern from the result. Deliberately minimal (docs/plan/phase-11-monorepo.md Step 1).
	 */
	static List<Path> resolveGlobs(Path root, List<String> rawPatterns) {
		Set<Path> included = new LinkedHashSet<>();
		Set<Path> excluded = new LinkedHashSet<>();
		for (String raw : rawPatterns) {
			if (raw == null) {
				continue;
			}
			String pattern = raw.strip();
			if (pattern.isEmpty()) {
				continue;
			}
			if (pattern.startsWith("!")) {
				excluded.addAll(expand(root, normalizePattern(pattern.substring(1))));
			} else {
				included.addAll(expand(root, normalizePattern(pattern)));
			}
		}
		included.removeAll(excluded);
		return included.stream().filter(ServiceDetector::isEligible).sorted().toList();
	}

	private static String normalizePattern(String p) {
		String cleaned = p.strip();
		while (cleaned.startsWith("/")) {
			cleaned = cleaned.substring(1);
		}
		while (cleaned.endsWith("/")) {
			cleaned = cleaned.substring(0, cleaned.length() - 1);
		}
		return cleaned;
	}

	private static List<Path> expand(Path root, String pattern) {
		if (pattern.isEmpty()) {
			return List.of();
		}
		List<Path> current = List.of(root);
		for (String segment : pattern.split("/")) {
			List<Path> next = new ArrayList<>();
			boolean wildcard = segment.equals("*") || segment.equals("**");
			for (Path base : current) {
				if (wildcard) {
					if (!Files.isDirectory(base)) {
						continue;
					}
					try (Stream<Path> children = Files.list(base)) {
						children.filter(Files::isDirectory).filter(ServiceDetector::isEligible).forEach(next::add);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				} else {
					Path candidate = base.resolve(segment);
					if (Files.isDirectory(candidate)) {
						next.add(candidate);
					}
				}
			}
			current = next;
		}
		return current;
	}

	private static boolean isEligible(Path p) {
		if (!Files.isDirectory(p)) {
			return false;
		}
		String name = p.getFileName().toString();
		return !name.startsWith(".") && !IGNORED_DIR_NAMES.contains(name);
	}

	private static boolean hasFallbackMarker(Path candidate) {
		return FALLBACK_MARKER_FILES.stream().anyMatch(marker -> Files.exists(candidate.resolve(marker)));
	}
}
