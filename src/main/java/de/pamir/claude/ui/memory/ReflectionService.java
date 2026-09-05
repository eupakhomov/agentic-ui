package de.pamir.claude.ui.memory;

import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.journal.SessionEventBus;
import de.pamir.claude.ui.journal.TranscriptDigest;
import de.pamir.claude.ui.library.EmbeddingClient;
import de.pamir.claude.ui.session.SessionEntity;
import de.pamir.claude.ui.session.SessionRepository;
import de.pamir.claude.ui.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * End-of-session memory retrospective: one structured system-session turn distills a
 * transcript into an episode summary plus semantic-memory ops (see docs/plan/phase-5.3-
 * memory-reflection.md, decisions 1-2). Depends on SessionService (for runSystemTurn);
 * SessionService never depends back on this — the close-time trigger is a Spring event
 * (ReflectionRequested) precisely to avoid that cycle, while the manual "Reflect now" endpoint
 * calls {@link #reflect} directly from the controller.
 */
@Service
public class ReflectionService {

	private static final Logger log = LoggerFactory.getLogger(ReflectionService.class);
	private static final Duration TIMEOUT = Duration.ofMinutes(3);
	private static final int MAX_OPS = 10;
	private static final Set<String> VALID_OPS = Set.of("create", "update", "archive");
	private static final Set<String> VALID_SCOPES = Set.of("ecosystem", "service");

	private final SessionRepository sessions;
	private final SessionService sessionService;
	private final EventJournal journal;
	private final SessionEventBus bus;
	private final SettingsService settings;
	private final MemoryDocService docService;
	private final MemoryRepository docs;
	private final MemoryEpisodeRepository episodes;
	private final MemoryProposalRepository proposals;
	private final EmbeddingClient embeddings;
	private final ObjectMapper mapper;
	private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

	public ReflectionService(SessionRepository sessions, SessionService sessionService, EventJournal journal,
							  SessionEventBus bus, SettingsService settings, MemoryDocService docService,
							  MemoryRepository docs, MemoryEpisodeRepository episodes,
							  MemoryProposalRepository proposals, EmbeddingClient embeddings, ObjectMapper mapper) {
		this.sessions = sessions;
		this.sessionService = sessionService;
		this.journal = journal;
		this.bus = bus;
		this.settings = settings;
		this.docService = docService;
		this.docs = docs;
		this.episodes = episodes;
		this.proposals = proposals;
		this.embeddings = embeddings;
		this.mapper = mapper;
	}

	@EventListener
	public void onReflectionRequested(ReflectionRequested event) {
		Thread.ofVirtual().name("reflect-" + event.sessionId()).start(() -> {
			try {
				reflect(event.sessionId());
			} catch (RuntimeException e) {
				log.warn("reflection failed for session {}: {}", event.sessionId(), e.getMessage());
			}
		});
	}

	/**
	 * Runs a reflection synchronously. Best-effort past this point: a failed system turn or
	 * unparseable response journals a warning and leaves reflectedSeq untouched (retryable),
	 * it never throws for those cases — only for the up-front validation below.
	 */
	public void reflect(UUID sessionId) {
		SessionEntity session = sessions.get(sessionId);
		if ("system".equals(session.kind())) {
			throw new IllegalArgumentException("system sessions are not reflected");
		}
		long lastSeq = journal.lastSeq(sessionId);
		if (lastSeq == 0 || (session.reflectedSeq() != null && session.reflectedSeq() >= lastSeq)) {
			throw new IllegalStateException("nothing to reflect: no turns completed since the last reflection");
		}
		// fail fast before spending a system turn — checked again atomically by the unique
		// index at proposal-insert time, but that would only surface after the expensive part
		if (settings.memoryReflectionApprovalRequired() && proposals.findPendingForSession(sessionId).isPresent()) {
			throw new IllegalStateException("a reflection proposal is already pending approval for this session");
		}
		if (!inFlight.add(sessionId)) {
			throw new IllegalStateException("a reflection is already in progress for this session");
		}
		try {
			runReflection(session, lastSeq);
		} finally {
			inFlight.remove(sessionId);
		}
	}

	/**
	 * Approves a pending proposal, optionally with edited episode text / ops (mirrors
	 * {@code permission_response}'s {@code updatedInput} — edit before allowing), and applies it
	 * exactly as the auto-apply path would have.
	 */
	public void approveProposal(UUID proposalId, String editedEpisode, JsonNode editedOps) {
		proposals.decide(proposalId, "APPROVED");
		var proposal = proposals.get(proposalId);
		SessionEntity session = sessions.get(proposal.sessionId());
		String episode = editedEpisode != null && !editedEpisode.isBlank() ? editedEpisode : proposal.episode();
		JsonNode ops = editedOps != null ? editedOps : proposal.ops();
		applyReflection(session, proposal.reflectedSeq(), episode, ops);
	}

	/** Discards a pending proposal — nothing is written to the vault; {@code reflectedSeq} stays unset, so a later "Reflect now" can try again. */
	public void discardProposal(UUID proposalId) {
		proposals.decide(proposalId, "DISCARDED");
		var proposal = proposals.get(proposalId);
		record(proposal.sessionId(), "reflection_discarded", mapper.createObjectNode().put("episode", proposal.episode()));
	}

	private void runReflection(SessionEntity session, long lastSeq) {
		String digest = TranscriptDigest.render(journal.readAfter(session.id(), 0));
		List<MemoryRepository.IndexEntry> index = docs.findIndex(session.repoPath());
		String prompt = buildPrompt(session, digest, index);
		String raw;
		try {
			raw = sessionService.runSystemTurn(prompt, settings.memoryReflectionModel(), TIMEOUT);
		} catch (RuntimeException e) {
			warn(session.id(), "reflection turn failed: " + e.getMessage());
			return;
		}
		JsonNode result;
		try {
			result = mapper.readTree(stripFences(raw));
		} catch (RuntimeException e) {
			warn(session.id(), "reflection response was not valid JSON: " + truncate(raw, 300));
			return;
		}
		String episodeSummary = result.path("episode").asText("");
		if (episodeSummary.isBlank()) {
			warn(session.id(), "reflection response missing an episode summary");
			return;
		}
		JsonNode ops = result.path("semantic");
		if (!ops.isArray()) {
			ops = mapper.createArrayNode();
		}
		if (settings.memoryReflectionApprovalRequired()) {
			var proposal = proposals.insert(session.id(), session.name(), session.repoPath(), lastSeq,
					episodeSummary, ops);
			ObjectNode payload = mapper.createObjectNode();
			payload.put("proposalId", proposal.id().toString());
			payload.put("episode", episodeSummary);
			payload.set("ops", ops);
			record(session.id(), "reflection_proposed", payload);
		} else {
			applyReflection(session, lastSeq, episodeSummary, ops);
		}
	}

	/** Writes the episode + semantic ops for real — the terminal step of both the auto-apply and the approve-proposal paths. */
	private void applyReflection(SessionEntity session, long reflectedSeq, String episodeSummary, JsonNode ops) {
		var episode = episodes.insert(session.id(), session.name(), session.repoPath(), episodeSummary);
		maybeEmbedEpisode(episode.id(), episodeSummary);

		List<String> created = new ArrayList<>();
		List<String> updated = new ArrayList<>();
		List<String> archived = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		int count = 0;
		for (JsonNode op : ops) {
			if (count++ >= MAX_OPS) {
				warnings.add("more than " + MAX_OPS + " semantic ops returned; extras ignored");
				break;
			}
			applyOp(session, op, created, updated, archived, warnings);
		}
		sessions.updateReflectedSeq(session.id(), reflectedSeq);
		ObjectNode payload = mapper.createObjectNode();
		payload.put("episode", episodeSummary);
		payload.set("created", mapper.valueToTree(created));
		payload.set("updated", mapper.valueToTree(updated));
		payload.set("archived", mapper.valueToTree(archived));
		payload.set("warnings", mapper.valueToTree(warnings));
		record(session.id(), "reflection_complete", payload);
	}

	private void applyOp(SessionEntity session, JsonNode op, List<String> created, List<String> updated,
						  List<String> archived, List<String> warnings) {
		String kind = op.path("op").asText("");
		String scope = op.path("scope").asText("");
		String name = op.path("name").asText("");
		if (!VALID_OPS.contains(kind) || !VALID_SCOPES.contains(scope) || !name.matches("[a-z0-9][a-z0-9-]*")) {
			warnings.add("skipped malformed semantic op: " + op);
			return;
		}
		String servicePath = "service".equals(scope) ? session.repoPath() : null;
		try {
			switch (kind) {
				case "create" -> {
					docService.write(scope, servicePath, name, op.path("description").asText(""),
							tags(op), op.path("content").asText(""));
					created.add(name);
				}
				case "update" -> {
					var existing = docs.findByScopeAndName(scope, servicePath, name);
					if (existing.isEmpty()) {
						warnings.add("update target not found, skipped: " + name);
						return;
					}
					docService.write(scope, servicePath, name, op.path("description").asText(""),
							tags(op), op.path("content").asText(""));
					updated.add(name);
				}
				case "archive" -> {
					var existing = docs.findByScopeAndName(scope, servicePath, name);
					if (existing.isEmpty()) {
						warnings.add("archive target not found, skipped: " + name);
						return;
					}
					docService.archive(existing.get().id());
					archived.add(name);
				}
				default -> { /* unreachable, guarded above */ }
			}
		} catch (RuntimeException e) {
			warnings.add("op on '" + name + "' failed: " + e.getMessage());
		}
	}

	private static List<String> tags(JsonNode op) {
		List<String> tags = new ArrayList<>();
		if (op.path("tags").isArray()) {
			op.path("tags").forEach(t -> tags.add(t.asText()));
		}
		return tags;
	}

	private void maybeEmbedEpisode(UUID episodeId, String summary) {
		if (!embeddings.configured()) {
			return;
		}
		try {
			episodes.upsertEmbedding(episodeId, embeddings.embed(summary, false), embeddings.model());
		} catch (RuntimeException e) {
			log.warn("embedding failed for episode {}: {}", episodeId, e.getMessage());
		}
	}

	private String buildPrompt(SessionEntity session, String digest, List<MemoryRepository.IndexEntry> index) {
		StringBuilder indexText = new StringBuilder();
		for (var entry : index) {
			indexText.append("- [").append(entry.scope()).append("] ").append(entry.name())
					.append(": ").append(entry.description())
					.append(" (tags: ").append(String.join(", ", entry.tags())).append(")\n");
		}
		return """
				You are retrospecting a finished coding session to extract durable memory. Read the
				transcript digest below and produce a JSON object with exactly two keys:

				"episode": a 3-6 sentence summary of what happened — goal, approach, outcome, failures,
				dead ends. This is an immutable log entry, write it as history, not advice.

				"semantic": an array of at most 8 ops, each {"op": "create"|"update"|"archive", "scope":
				"ecosystem"|"service", "name": "kebab-slug", "description": "...", "tags": ["..."],
				"content": "markdown body", "reason": "why this is worth remembering"}. Only durable,
				non-obvious learnings — architectural decisions, gotchas, conventions, user preferences —
				never a restatement of what the repo's own docs already say. Prefer "update" of an
				existing indexed doc (listed below) over "create". Use scope "ecosystem" only for facts
				that apply beyond this one service/repo. Link related memories inline in "content" using
				[[slug]] wikilinks — liberally, including a link to a slug that doesn't exist yet if it
				marks a real gap. If nothing is worth remembering, return an empty "semantic" array — do
				not invent content to fill it. Return ONLY the JSON object, no prose, no code fences.

				Existing memory index for this session's visible scopes (service: %s):
				%s

				Transcript digest:
				%s
				""".formatted(session.repoPath(), indexText.isEmpty() ? "(none yet)" : indexText, digest);
	}

	private void warn(UUID sessionId, String message) {
		log.warn("reflection warning for session {}: {}", sessionId, message);
		record(sessionId, "warning", mapper.createObjectNode().put("message", "reflection: " + message));
	}

	private void record(UUID sessionId, String type, JsonNode payload) {
		bus.publish(sessionId, journal.append(sessionId, type, payload));
	}

	private static String stripFences(String raw) {
		String cleaned = raw == null ? "" : raw.strip();
		if (cleaned.startsWith("```")) {
			cleaned = cleaned.replaceFirst("^```(json)?", "").replaceFirst("```$", "").strip();
		}
		return cleaned;
	}

	private static String truncate(String s, int max) {
		return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
	}
}
