package de.pamir.claude.ui.memory;

import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.library.EmbeddingClient;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the semantic-memory write path: the Markdown file is the source of truth (decision 6,
 * docs/plan/phase-5.3-memory-reflection.md), memory_doc is a rebuildable index over it. Two
 * entry points: {@link #write} for content this app is authoring (reflection ops, UI edits —
 * frontmatter is regenerated), {@link #reindexFile} for content a human wrote directly on disk
 * (sync — frontmatter is only read, the file is never rewritten).
 */
@Service
public class MemoryDocService {

	private static final Logger log = LoggerFactory.getLogger(MemoryDocService.class);
	private static final int PAGE_CHARS = 4_000;
	private static final int EMBED_CONTENT_CHARS = 16_000;

	private final SettingsService settings;
	private final MemoryPaths paths;
	private final MemoryRepository docs;
	private final EmbeddingClient embeddings;

	public MemoryDocService(SettingsService settings, MemoryPaths paths, MemoryRepository docs,
							 EmbeddingClient embeddings) {
		this.settings = settings;
		this.paths = paths;
		this.docs = docs;
		this.embeddings = embeddings;
	}

	public record Page(MemoryRepository.MemoryDoc doc, String pageContent, int page, int totalPages,
						List<MemoryRepository.LinkRef> outgoing, List<MemoryRepository.LinkRef> backlinks) {
	}

	// --- authored writes (reflection ops, UI edit) ---

	public MemoryRepository.MemoryDoc write(String scope, String servicePath, String name, String description,
											 List<String> tags, String content) {
		validateScope(scope, servicePath);
		String slug = validateName(name);
		Path dir = "ecosystem".equals(scope) ? paths.ecosystemDir(settings.memoryRoot())
				: paths.serviceDir(settings.memoryRoot(), servicePath);
		Path file = dir.resolve(slug + ".md");
		String today = LocalDate.now(ZoneOffset.UTC).toString();
		String rendered = Frontmatter.render(slug, description, tags, scope, "service".equals(scope) ? servicePath : null,
				today, content);
		try {
			Files.createDirectories(dir);
			Files.writeString(file, rendered, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("failed to write memory doc " + file, e);
		}
		String relPath = paths.relPath(settings.memoryRoot(), file);
		String hash = sha256(rendered);
		return index(scope, servicePath, relPath, slug, description, tags, content, hash);
	}

	public void archive(UUID id) {
		docs.updateStatus(id, "ARCHIVED");
		docs.danglePointingAt(id);
	}

	public void restore(UUID id) {
		MemoryRepository.MemoryDoc doc = docs.get(id);
		docs.updateStatus(id, "ACTIVE");
		docs.reresolveDangling(doc);
	}

	// --- sync path (human-edited files on disk; see MemorySyncService) ---

	/** Indexes whatever is currently on disk at {@code file} — never rewrites it. */
	public void reindexFile(Path file, String scope, String servicePath) {
		String slug = stripMdExtension(file.getFileName().toString());
		String raw;
		try {
			raw = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("failed to read memory doc " + file, e);
		}
		String hash = sha256(raw);
		String relPath = paths.relPath(settings.memoryRoot(), file);
		var existing = docs.findByRelPath(relPath);
		if (existing.isPresent() && existing.get().contentHash().equals(hash)) {
			return; // unchanged since last index
		}
		Frontmatter.Doc parsed = Frontmatter.parse(raw);
		String description = str(parsed.meta().get("description"), "");
		List<String> tags = tagsFrom(parsed.meta().get("tags"));
		index(scope, servicePath, relPath, slug, description, tags, parsed.body(), hash);
	}

	/** Called by sync when a previously-indexed file has vanished from disk. */
	public void archiveMissing(UUID id) {
		archive(id);
	}

	private MemoryRepository.MemoryDoc index(String scope, String servicePath, String relPath, String slug,
											  String description, List<String> tags, String content, String hash) {
		var existing = docs.findByRelPath(relPath);
		MemoryRepository.MemoryDoc doc;
		if (existing.isPresent()) {
			docs.updateContent(existing.get().id(), description, tags, content, hash);
			if ("ARCHIVED".equals(existing.get().status())) {
				docs.updateStatus(existing.get().id(), "ACTIVE");
			}
			doc = docs.get(existing.get().id());
		} else {
			doc = docs.insert(scope, servicePath, relPath, slug, description, tags, content, hash);
		}
		docs.replaceLinks(doc.id(), Wikilinks.extract(content), scope, servicePath);
		docs.reresolveDangling(doc);
		maybeEmbed(doc);
		return doc;
	}

	// --- reads ---

	public Page read(UUID id, int page) {
		MemoryRepository.MemoryDoc doc = docs.get(id);
		int totalPages = Math.max(1, (doc.content().length() + PAGE_CHARS - 1) / PAGE_CHARS);
		int p = Math.max(1, Math.min(page, totalPages));
		int from = (p - 1) * PAGE_CHARS;
		int to = Math.min(doc.content().length(), from + PAGE_CHARS);
		String pageContent = doc.content().substring(from, to);
		return new Page(doc, pageContent, p, totalPages, docs.outgoing(doc.id()), docs.backlinks(doc.id()));
	}

	/** Same as {@link #read} but only returns related links visible to the given scope (agent tool reads). */
	public Page readScoped(UUID id, int page, String servicePath) {
		Page full = read(id, page);
		return new Page(full.doc(), full.pageContent(), full.page(), full.totalPages(),
				filterVisible(full.outgoing(), servicePath), filterVisible(full.backlinks(), servicePath));
	}

	private List<MemoryRepository.LinkRef> filterVisible(List<MemoryRepository.LinkRef> refs, String servicePath) {
		return refs.stream().filter(r -> {
			if (r.docId() == null) {
				return true; // dangling links are always visible — they're informative regardless of scope
			}
			MemoryRepository.MemoryDoc target = docs.get(r.docId());
			return "ecosystem".equals(target.scope()) || servicePath.equals(target.servicePath());
		}).toList();
	}

	/** Best-effort; a Voyage failure never blocks the write. */
	private void maybeEmbed(MemoryRepository.MemoryDoc doc) {
		if (!embeddings.configured()) {
			return;
		}
		try {
			String text = doc.name() + "\n" + doc.description() + "\n" + truncate(doc.content(), EMBED_CONTENT_CHARS);
			docs.upsertEmbedding(doc.id(), embeddings.embed(text, false), embeddings.model());
		} catch (RuntimeException e) {
			log.warn("embedding failed for memory doc {}: {}", doc.id(), e.getMessage());
		}
	}

	private static void validateScope(String scope, String servicePath) {
		if (!Set.of("ecosystem", "service").contains(scope)) {
			throw new IllegalArgumentException("scope must be 'ecosystem' or 'service'");
		}
		if ("service".equals(scope) && (servicePath == null || servicePath.isBlank())) {
			throw new IllegalArgumentException("servicePath is required for scope 'service'");
		}
	}

	private static String validateName(String name) {
		if (name == null || !name.matches("[a-z0-9][a-z0-9-]*")) {
			throw new IllegalArgumentException("name must be a kebab-case slug: " + name);
		}
		return name;
	}

	private static String stripMdExtension(String fileName) {
		return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
	}

	private static String str(Object value, String fallback) {
		return value == null ? fallback : value.toString();
	}

	@SuppressWarnings("unchecked")
	private static List<String> tagsFrom(Object value) {
		if (value instanceof List<?> list) {
			return list.stream().map(String::valueOf).toList();
		}
		return List.of();
	}

	private static String truncate(String s, int max) {
		return s.length() > max ? s.substring(0, max) : s;
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
