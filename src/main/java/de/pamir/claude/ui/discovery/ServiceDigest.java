package de.pamir.claude.ui.discovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bounded, cheap "what is this repo" digest fed to the discovery system turn — README/CLAUDE.md/
 * AGENTS.md, a manifest name+description sniff, and a shallow directory listing. Deliberately NOT
 * an agentic exploration (docs/plan/phase-8-service-discovery.md decision 8): the backend reads a
 * small, bounded set of files itself, same posture as the library's AI-fill.
 */
public final class ServiceDigest {

	private static final List<String> DOC_FILES = List.of("README.md", "README", "CLAUDE.md", "AGENTS.md");
	private static final int CHARS_PER_DOC = 4_000;
	private static final int LISTING_DEPTH = 2;
	private static final Set<String> SKIPPED_DIRS =
			Set.of(".git", "node_modules", "target", "dist", "build", ".repo-cache");

	private ServiceDigest() {
	}

	public static String render(Path repoPath) {
		StringBuilder sb = new StringBuilder();
		sb.append("Service folder: ").append(repoPath).append('\n');
		manifestSummary(repoPath).ifPresent(m -> sb.append(m).append('\n'));
		for (String docName : DOC_FILES) {
			Path doc = repoPath.resolve(docName);
			if (Files.isRegularFile(doc)) {
				sb.append("=== ").append(docName).append(" ===\n").append(readTruncated(doc)).append("\n\n");
			}
		}
		sb.append("=== directory listing (depth ").append(LISTING_DEPTH).append(") ===\n");
		listing(repoPath, 0, sb);
		return sb.toString();
	}

	private static Optional<String> manifestSummary(Path repoPath) {
		Path packageJson = repoPath.resolve("package.json");
		if (Files.isRegularFile(packageJson)) {
			String content = readTruncated(packageJson);
			String name = firstMatch(content, "\"name\"\\s*:\\s*\"([^\"]*)\"");
			String description = firstMatch(content, "\"description\"\\s*:\\s*\"([^\"]*)\"");
			if (name != null || description != null) {
				return Optional.of("package.json: " + orEmpty(name) + " — " + orEmpty(description));
			}
		}
		Path pom = repoPath.resolve("pom.xml");
		if (Files.isRegularFile(pom)) {
			String content = readTruncated(pom);
			String name = firstMatch(content, "<name>([^<]*)</name>");
			String description = firstMatch(content, "<description>([^<]*)</description>");
			if (name != null || description != null) {
				return Optional.of("pom.xml: " + orEmpty(name) + " — " + orEmpty(description));
			}
		}
		return Optional.empty();
	}

	private static String orEmpty(String s) {
		return s == null ? "" : s.strip();
	}

	private static String firstMatch(String content, String pattern) {
		var m = Pattern.compile(pattern).matcher(content);
		return m.find() ? m.group(1) : null;
	}

	private static void listing(Path dir, int depth, StringBuilder sb) {
		if (depth > LISTING_DEPTH) {
			return;
		}
		try (Stream<Path> children = Files.list(dir)) {
			for (Path child : children.sorted().toList()) {
				String name = child.getFileName().toString();
				if (Files.isDirectory(child)) {
					if (SKIPPED_DIRS.contains(name)) {
						continue;
					}
					sb.append("  ".repeat(depth)).append(name).append("/\n");
					listing(child, depth + 1, sb);
				} else {
					sb.append("  ".repeat(depth)).append(name).append('\n');
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String readTruncated(Path file) {
		try {
			byte[] bytes = Files.readAllBytes(file);
			String content = new String(bytes, StandardCharsets.UTF_8);
			return content.length() > CHARS_PER_DOC ? content.substring(0, CHARS_PER_DOC) + "\n[truncated]" : content;
		} catch (IOException e) {
			return "[unreadable: " + e.getMessage() + "]";
		}
	}
}
