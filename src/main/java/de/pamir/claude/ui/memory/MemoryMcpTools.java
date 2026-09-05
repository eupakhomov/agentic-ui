package de.pamir.claude.ui.memory;

import de.pamir.claude.ui.library.EmbeddingClient;
import de.pamir.claude.ui.session.SessionRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The three memory tools every session gets, in-process via Spring AI's MCP server (decision
 * 12a, docs/plan/phase-5.3-memory-reflection.md) — drawer-label-before-folder hierarchy:
 * {@code memory_tags} → {@code memory_search} → {@code memory_read}. Each takes an explicit
 * {@code sessionId} (decision 12b) rather than relying on transport-level context; the calling
 * session learns its own id from the episodic-window system-prompt block injected at spawn.
 */
@Component
public class MemoryMcpTools {

	private static final int DEFAULT_SEARCH_LIMIT = 10;

	public record SearchResult(String name, String scope, String description, List<String> tags) {
	}

	public record RelatedDoc(String name, String description, boolean dangling) {
	}

	public record ReadResult(String name, String scope, String description, List<String> tags, String content,
							  int page, int totalPages, List<RelatedDoc> outgoing, List<RelatedDoc> backlinks) {
	}

	private final SessionRepository sessions;
	private final MemoryRepository docs;
	private final MemoryDocService docService;
	private final EmbeddingClient embeddings;

	public MemoryMcpTools(SessionRepository sessions, MemoryRepository docs, MemoryDocService docService,
						   EmbeddingClient embeddings) {
		this.sessions = sessions;
		this.docs = docs;
		this.docService = docService;
		this.embeddings = embeddings;
	}

	@McpTool(name = "memory_tags",
			annotations = @McpTool.McpAnnotations(title = "Memory tags", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = "List the memory tags available to you (ecosystem-wide plus your own "
					+ "service), with how many memories carry each — the drawer labels before you "
					+ "search or read.")
	public Map<String, Long> memoryTags(
			@McpToolParam(required = true, description = "Your session id, given in your system prompt")
			String sessionId) {
		return docs.tagCounts(repoPathOf(sessionId));
	}

	@McpTool(name = "memory_search",
			annotations = @McpTool.McpAnnotations(title = "Search memory", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = "Search long-term memory (your own service's memories plus ecosystem-wide "
					+ "ones) by natural language or exact identifier. Returns names, scopes and one-line "
					+ "descriptions only — call memory_read on a name to get the actual content.")
	public List<SearchResult> memorySearch(
			@McpToolParam(required = true, description = "Your session id, given in your system prompt")
			String sessionId,
			@McpToolParam(required = true, description = "Search query — a question, phrase, or exact identifier")
			String query,
			@McpToolParam(required = false, description = "Optional: only memories carrying at least one of these tags")
			List<String> tags) {
		String servicePath = repoPathOf(sessionId);
		float[] embedding = embeddings.configured() ? embeddings.embed(query, true) : null;
		return docs.hybridSearch(query, embedding, servicePath, tags, DEFAULT_SEARCH_LIMIT).stream()
				.map(hit -> new SearchResult(hit.doc().name(), hit.doc().scope(), hit.doc().description(),
						hit.doc().tags()))
				.toList();
	}

	@McpTool(name = "memory_read",
			annotations = @McpTool.McpAnnotations(title = "Read memory", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = "Read one page of a memory's content by name (from memory_search), plus its "
					+ "related memories (one hop of wikilinks in both directions) as names and "
					+ "descriptions only. Content is paginated — call again with a higher page number "
					+ "if totalPages > page.")
	public ReadResult memoryRead(
			@McpToolParam(required = true, description = "Your session id, given in your system prompt")
			String sessionId,
			@McpToolParam(required = true, description = "The memory's name, as returned by memory_search")
			String name,
			@McpToolParam(required = false, description = "Page number, 1-based; defaults to 1")
			Integer page) {
		String servicePath = repoPathOf(sessionId);
		MemoryRepository.MemoryDoc doc = docs.findVisibleByName(servicePath, name)
				.orElseThrow(() -> new NoSuchElementException("no memory named '" + name + "' visible to this session"));
		var result = docService.readScoped(doc.id(), page == null ? 1 : page, servicePath);
		return new ReadResult(doc.name(), doc.scope(), doc.description(), doc.tags(), result.pageContent(),
				result.page(), result.totalPages(), links(result.outgoing()), links(result.backlinks()));
	}

	private static List<RelatedDoc> links(List<MemoryRepository.LinkRef> refs) {
		return refs.stream().map(r -> new RelatedDoc(
				r.dangling() ? r.slug() : r.name(),
				r.dangling() ? "(not written yet)" : r.description(),
				r.dangling())).toList();
	}

	private String repoPathOf(String sessionId) {
		UUID id;
		try {
			id = UUID.fromString(sessionId);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("sessionId is not a valid session id: " + sessionId);
		}
		return sessions.get(id).repoPath();
	}
}
