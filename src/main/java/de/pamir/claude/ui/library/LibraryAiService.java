package de.pamir.claude.ui.library;

import de.pamir.claude.ui.session.SessionService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * "Magic fill" for scanned candidates: batches file contents through the singleton Haiku
 * system session (same pattern as TicketImportService) and returns name/description/tags
 * suggestions per path. Batched to bound prompt size; the frontend chunks big selections.
 */
@Service
public class LibraryAiService {

	private static final Duration TIMEOUT = Duration.ofSeconds(60);
	private static final int BATCH_SIZE = 5;
	private static final int CONTENT_CHARS_PER_FILE = 8_000;

	public record FilledMeta(String path, String name, String description, List<String> tags) {
	}

	private final SessionService sessionService;
	private final AssetScanService scanner;
	private final ObjectMapper mapper;

	public LibraryAiService(SessionService sessionService, AssetScanService scanner, ObjectMapper mapper) {
		this.sessionService = sessionService;
		this.scanner = scanner;
		this.mapper = mapper;
	}

	public List<FilledMeta> fill(String type, String ref, List<String> paths) {
		if (paths == null || paths.isEmpty()) {
			throw new IllegalArgumentException("paths is required");
		}
		Path root = scanner.resolveRoot(type, ref);
		List<FilledMeta> results = new ArrayList<>();
		for (int i = 0; i < paths.size(); i += BATCH_SIZE) {
			results.addAll(fillBatch(root, paths.subList(i, Math.min(i + BATCH_SIZE, paths.size()))));
		}
		return results;
	}

	private List<FilledMeta> fillBatch(Path root, List<String> paths) {
		StringBuilder files = new StringBuilder();
		for (String path : paths) {
			files.append("=== FILE: ").append(path).append(" ===\n")
					.append(readContent(root, path)).append("\n\n");
		}
		String prompt = ("You are cataloguing reusable Claude Code skills and agents for a library. For each file "
				+ "below, write metadata. Respond with ONLY a JSON array — no markdown fences, no commentary — of "
				+ "objects of the form {\"path\": \"the FILE path exactly as given\", \"name\": \"short, "
				+ "human-readable, 2-5 words\", \"description\": \"one sentence (max ~25 words) saying what it does "
				+ "and when to use it\", \"tags\": [\"3-6 short lowercase keyword tags\"]}. Return one object per "
				+ "file, in the same order.\n\n%s").formatted(files);
		String raw = sessionService.runSystemTurn(prompt, TIMEOUT);
		return parse(raw, paths);
	}

	private String readContent(Path root, String path) {
		Path file = ".".equals(path) ? root : root.resolve(path).normalize();
		// real-path check so a symlink inside a (potentially third-party) source can't
		// smuggle an outside file's content into the prompt
		if (!file.startsWith(root) || !AssetScanService.staysInside(root, file)) {
			throw new IllegalArgumentException("path escapes the source root: " + path);
		}
		if (Files.isDirectory(file)) {
			file = file.resolve("SKILL.md");
		}
		try {
			byte[] bytes = Files.readAllBytes(file);
			String content = new String(bytes, StandardCharsets.UTF_8);
			return content.length() > CONTENT_CHARS_PER_FILE
					? content.substring(0, CONTENT_CHARS_PER_FILE) + "\n[truncated]" : content;
		} catch (IOException e) {
			return "[unreadable: " + e.getMessage() + "]";
		}
	}

	private List<FilledMeta> parse(String raw, List<String> requestedPaths) {
		JsonNode node;
		try {
			node = mapper.readTree(stripFences(raw));
		} catch (RuntimeException e) {
			throw new IllegalStateException("could not parse AI-fill response: " + truncate(raw));
		}
		List<FilledMeta> results = new ArrayList<>();
		for (JsonNode item : node) {
			String path = item.path("path").asText("").strip();
			String name = item.path("name").asText("").strip();
			if (path.isBlank() || name.isBlank() || !requestedPaths.contains(path)) {
				continue;
			}
			List<String> tags = new ArrayList<>();
			for (JsonNode tag : item.path("tags")) {
				String value = tag.asText("").strip().toLowerCase(java.util.Locale.ROOT);
				if (!value.isBlank()) {
					tags.add(value);
				}
			}
			results.add(new FilledMeta(path, name, item.path("description").asText("").strip(), tags));
		}
		if (results.isEmpty()) {
			throw new IllegalStateException("AI-fill returned no usable entries: " + truncate(raw));
		}
		return results;
	}

	private static String stripFences(String raw) {
		String cleaned = raw == null ? "" : raw.strip();
		if (cleaned.startsWith("```")) {
			cleaned = cleaned.replaceFirst("^```(json)?", "").replaceFirst("```$", "").strip();
		}
		return cleaned;
	}

	private static String truncate(String s) {
		if (s == null) {
			return "";
		}
		return s.length() > 300 ? s.substring(0, 300) + "…" : s;
	}
}
