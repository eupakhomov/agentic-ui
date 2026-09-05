package de.pamir.claude.ui.memory;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Resolves the on-disk layout of the memory vault: {@code <root>/ecosystem/*.md} and
 * {@code <root>/services/<slug>/*.md}. The DB stores a service's canonical repo path
 * (session.repoPath, exact string); the directory name is a human-friendly slug of the
 * repo's basename, disambiguated with a content-hash suffix on collision (decision 5).
 * A hidden {@code .repo-path} marker file in each service dir records which canonical
 * path claimed that slug, so a second, differently-pathed repo with the same basename
 * doesn't silently share a directory.
 */
@Component
public class MemoryPaths {

	private static final String MARKER_FILE = ".repo-path";

	public Path ecosystemDir(String root) {
		return Path.of(root).resolve("ecosystem");
	}

	public Path servicesRoot(String root) {
		return Path.of(root).resolve("services");
	}

	/** Finds or creates the service directory for a repo path, writing/checking its marker file. */
	public Path serviceDir(String root, String repoPath) {
		Path servicesRoot = servicesRoot(root);
		String baseSlug = slugify(baseName(repoPath));
		Path candidate = servicesRoot.resolve(baseSlug);
		String existingMarker = readMarker(candidate);
		if (existingMarker == null) {
			createWithMarker(candidate, repoPath);
			return candidate;
		}
		if (existingMarker.equals(repoPath)) {
			return candidate;
		}
		String fallbackSlug = baseSlug + "-" + sha256(repoPath).substring(0, 8);
		Path fallback = servicesRoot.resolve(fallbackSlug);
		String fallbackMarker = readMarker(fallback);
		if (fallbackMarker == null) {
			createWithMarker(fallback, repoPath);
		} else if (!fallbackMarker.equals(repoPath)) {
			// Astronomically unlikely (both the basename AND its hash suffix collided with a
			// different repo); fail loudly rather than silently mixing two services' memory.
			throw new IllegalStateException("memory service slug collision for " + repoPath);
		}
		return fallback;
	}

	/** relPath is what memory_doc.rel_path stores — always forward-slash, relative to the vault root. */
	public String relPath(String root, Path file) {
		return Path.of(root).relativize(file).toString().replace('\\', '/');
	}

	public Path resolve(String root, String relPath) {
		return Path.of(root).resolve(relPath);
	}

	private static String readMarker(Path serviceDir) {
		Path marker = serviceDir.resolve(MARKER_FILE);
		if (!Files.exists(marker)) {
			return null;
		}
		try {
			return Files.readString(marker, StandardCharsets.UTF_8).strip();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void createWithMarker(Path serviceDir, String repoPath) {
		try {
			Files.createDirectories(serviceDir);
			Files.writeString(serviceDir.resolve(MARKER_FILE), repoPath, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String baseName(String repoPath) {
		String name = Path.of(repoPath).getFileName() == null ? repoPath : Path.of(repoPath).getFileName().toString();
		return name.isBlank() ? "service" : name;
	}

	private static String slugify(String name) {
		String slug = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]+", "-")
				.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
		return slug.isBlank() ? "service" : slug;
	}

	static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
