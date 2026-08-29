package de.pamir.claude.ui.library;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans a source (local dir, or GitHub repo via RepoCacheService) for skill/agent
 * candidates. Convention-first classification: a directory containing SKILL.md is one
 * skill (never descended into); a standalone .md under an agent-ish path segment or with
 * name+description frontmatter is an agent; a bare "skill"/"agent" substring in the name
 * is only a low-confidence fallback. Hashes are SHA-256 of source content — a skill's
 * hash covers its whole directory (sorted relative-path + file-hash pairs).
 */
@Service
public class AssetScanService {

	public record Candidate(String path, String kind, String confidence, String name, String description,
							 String hash, long sizeBytes, boolean alreadyImported, boolean changedSinceImport) {
	}

	public record ScanResult(String type, String ref, List<Candidate> candidates) {
	}

	private static final int MAX_DEPTH = 6;
	private static final Set<String> SKIPPED_DIRS = Set.of(".git", "node_modules", ".repo-cache");
	private static final Pattern FRONTMATTER_FIELD = Pattern.compile("^(name|description):\\s*(.+)$");
	private static final Pattern AGENT_SEGMENT = Pattern.compile("(^|[^a-z])agents?([^a-z]|$)");

	private final RepoCacheService repoCache;
	private final AssetSourceRepository sources;
	private final LibraryRepository assets;

	public AssetScanService(RepoCacheService repoCache, AssetSourceRepository sources, LibraryRepository assets) {
		this.repoCache = repoCache;
		this.sources = sources;
		this.assets = assets;
	}

	/** Local root directory for a source; fetches/refreshes the clone for repo sources. */
	public Path resolveRoot(String type, String ref) {
		if ("repo".equals(type)) {
			return repoCache.fetch(ref);
		}
		Path root = Path.of(ref);
		if (!Files.isDirectory(root)) {
			throw new IllegalArgumentException("not a directory: " + ref);
		}
		return root;
	}

	public ScanResult scan(String type, String ref) {
		Path root = resolveRoot(type, ref);
		List<Candidate> found = new ArrayList<>();
		walk(root, root, 0, found);
		found.sort((a, b) -> a.path().compareTo(b.path()));
		return new ScanResult(type, ref, markImported(ref, found));
	}

	private void walk(Path root, Path dir, int depth, List<Candidate> found) {
		if (depth > MAX_DEPTH) {
			return;
		}
		if (Files.exists(dir.resolve("SKILL.md"))) {
			found.add(skillCandidate(root, dir));
			return;
		}
		try (Stream<Path> children = Files.list(dir)) {
			for (Path child : children.sorted().toList()) {
				if (!staysInside(root, child)) {
					continue; // symlink escaping the source — never read through it
				}
				String childName = child.getFileName().toString();
				if (Files.isDirectory(child)) {
					if (!SKIPPED_DIRS.contains(childName)) {
						walk(root, child, depth + 1, found);
					}
				} else if (childName.endsWith(".md")) {
					classifyFile(root, child, found);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** True iff the entry's real path (symlinks resolved) is still under the root's real path. */
	public static boolean staysInside(Path root, Path p) {
		try {
			return p.toRealPath().startsWith(root.toRealPath());
		} catch (IOException e) {
			return false;
		}
	}

	private void classifyFile(Path root, Path file, List<Candidate> found) {
		String relative = root.relativize(file).toString();
		String lowerPath = relative.toLowerCase(Locale.ROOT);
		Map<String, String> frontmatter = parseFrontmatter(readHead(file));
		boolean agentPath = AGENT_SEGMENT.matcher(lowerPath).find();
		boolean hasMeta = frontmatter.containsKey("name") && frontmatter.containsKey("description");
		String kind;
		String confidence;
		if (agentPath) {
			kind = "agent";
			confidence = "high";
		} else if (lowerPath.contains("skill")) {
			kind = "skill";
			confidence = "low";
		} else if (hasMeta) {
			kind = "agent";
			confidence = "low";
		} else {
			return;
		}
		found.add(fileCandidate(root, file, kind, confidence, frontmatter));
	}

	private Candidate skillCandidate(Path root, Path dir) {
		Map<String, String> frontmatter = parseFrontmatter(readHead(dir.resolve("SKILL.md")));
		String name = frontmatter.getOrDefault("name", dir.getFileName().toString());
		String relative = root.equals(dir) ? "." : root.relativize(dir).toString();
		long size;
		try (Stream<Path> walk = Files.walk(dir)) {
			size = walk.filter(Files::isRegularFile).mapToLong(p -> {
				try {
					return Files.size(p);
				} catch (IOException e) {
					return 0;
				}
			}).sum();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return new Candidate(relative, "skill", "high", name, frontmatter.getOrDefault("description", ""),
				hashTree(dir), size, false, false);
	}

	private Candidate fileCandidate(Path root, Path file, String kind, String confidence,
									 Map<String, String> frontmatter) {
		String baseName = file.getFileName().toString().replaceFirst("\\.md$", "");
		String name = frontmatter.getOrDefault("name", baseName);
		long size;
		try {
			size = Files.size(file);
		} catch (IOException e) {
			size = 0;
		}
		return new Candidate(root.relativize(file).toString(), kind, confidence, name,
				frontmatter.getOrDefault("description", ""), hashFile(file), size, false, false);
	}

	private List<Candidate> markImported(String ref, List<Candidate> found) {
		var source = sources.findByRef(ref);
		if (source.isEmpty()) {
			return found;
		}
		Map<String, LibraryRepository.AssetEntity> byPath = assets.findBySource(source.get().id()).stream()
				.filter(a -> a.sourcePath() != null)
				.collect(Collectors.toMap(LibraryRepository.AssetEntity::sourcePath, Function.identity(),
						(a, b) -> a));
		return found.stream().map(c -> {
			var existing = byPath.get(c.path());
			if (existing == null) {
				return c;
			}
			return new Candidate(c.path(), c.kind(), c.confidence(), c.name(), c.description(), c.hash(),
					c.sizeBytes(), true, !existing.contentHash().equals(c.hash()));
		}).toList();
	}

	// --- content hashing ---

	public String hashFile(Path file) {
		try {
			return sha256(Files.readAllBytes(file));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Order-stable digest over every file in the tree: sha256 of "relpath\nfilehash\n" lines. */
	public String hashTree(Path dir) {
		try (Stream<Path> walk = Files.walk(dir)) {
			String manifest = walk.filter(Files::isRegularFile)
					.filter(p -> staysInside(dir, p))
					.map(p -> dir.relativize(p).toString().replace('\\', '/') + "\n" + hashFile(p) + "\n")
					.sorted()
					.collect(Collectors.joining());
			return sha256(manifest.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public String hash(Path path) {
		return Files.isDirectory(path) ? hashTree(path) : hashFile(path);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// --- frontmatter ---

	private static String readHead(Path file) {
		try {
			byte[] bytes = Files.readAllBytes(file);
			return new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}

	private static Map<String, String> parseFrontmatter(String content) {
		Map<String, String> fields = new java.util.HashMap<>();
		List<String> lines = content.lines().toList();
		if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
			return fields;
		}
		for (String line : lines.subList(1, lines.size())) {
			if (line.strip().equals("---")) {
				break;
			}
			Matcher matcher = FRONTMATTER_FIELD.matcher(line.strip());
			if (matcher.matches()) {
				fields.put(matcher.group(1), matcher.group(2).strip());
			}
		}
		return fields;
	}
}
