package de.pamir.claude.ui.library;

import de.pamir.claude.ui.config.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Local clone cache for library repo sources, fetched exclusively through the gh CLI so the
 * user's existing `gh auth login` covers private repos (GitHub-only for now — a plain-git
 * fallback for other remotes can slot in here later). Refs are full GitHub URLs or
 * owner/repo shorthand, both accepted by `gh repo clone`. Refresh failures fall back to the
 * cached copy — the sync loop records the error instead of losing the source.
 */
@Service
public class RepoCacheService {

	private static final Logger log = LoggerFactory.getLogger(RepoCacheService.class);
	private static final long CLONE_TIMEOUT_SECONDS = 180;
	private static final long SYNC_TIMEOUT_SECONDS = 60;

	private final SettingsService settings;

	public RepoCacheService(SettingsService settings) {
		this.settings = settings;
	}

	/**
	 * Refs we hand to gh: an http(s) URL or owner/repo — never anything flag-shaped.
	 * Both owner and repo must START with an alphanumeric so no matching ref can begin
	 * with '-' (gh's `--` sits after the ref to delimit git passthrough flags, so the
	 * ref itself is in a flag-parsing position and must be validated, not escaped).
	 */
	private static final java.util.regex.Pattern SAFE_REF = java.util.regex.Pattern
			.compile("^(https?://[\\w./:-]+|[A-Za-z0-9][\\w.-]*/[A-Za-z0-9][\\w.-]*)$");

	/** Clones on first use, refreshes (fast-forward) afterwards; returns the local repo root. */
	public Path fetch(String ref) {
		if (ref == null || ref.startsWith("-") || !SAFE_REF.matcher(ref).matches()) {
			throw new IllegalArgumentException("invalid repo ref (expected a GitHub URL or owner/repo): " + ref);
		}
		Path cacheRoot = Path.of(settings.librarySkillsRoot(), ".repo-cache");
		Path target = cacheRoot.resolve(slug(ref));
		try {
			Files.createDirectories(cacheRoot);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (Files.isDirectory(target.resolve(".git"))) {
			GhResult sync = gh(target, SYNC_TIMEOUT_SECONDS, "gh", "repo", "sync");
			if (!sync.ok()) {
				log.warn("library repo {} refresh failed ({}); using cached copy", ref, sync.output());
			}
		} else {
			GhResult clone = gh(cacheRoot, CLONE_TIMEOUT_SECONDS,
					"gh", "repo", "clone", ref, target.toString(), "--", "--depth", "1");
			if (!clone.ok()) {
				throw new IllegalStateException("gh repo clone failed for " + ref + ": " + clone.output());
			}
		}
		return target;
	}

	private record GhResult(boolean ok, String output) {
	}

	private GhResult gh(Path workingDir, long timeoutSeconds, String... command) {
		try {
			Process process = new ProcessBuilder(command).directory(workingDir.toFile()).start();
			String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
			String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return new GhResult(false, command[2] + " timed out");
			}
			return new GhResult(process.exitValue() == 0, stderr.isBlank() ? stdout : stderr);
		} catch (IOException e) {
			throw new IllegalStateException(
					"gh CLI not available (" + e.getMessage() + "); install gh and run `gh auth login`");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new GhResult(false, "interrupted");
		}
	}

	private static String slug(String ref) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(ref.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
