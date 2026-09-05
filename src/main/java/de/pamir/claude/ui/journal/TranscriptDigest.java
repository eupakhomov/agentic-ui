package de.pamir.claude.ui.journal;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a session's journal as text, in two shapes sharing the same per-event-type logic:
 * {@link #render} is a capped digest for the reflection prompt (see
 * docs/plan/phase-5.3-memory-reflection.md); {@link #renderMarkdown} is the uncapped, human-
 * facing transcript export (phase-5-extensions.md 5.9, {@code GET
 * /api/sessions/{id}/export.md}). Both: user/assistant text, tool calls collapsed to name +
 * one-line args, errors, permission denials, a costs footer.
 */
public final class TranscriptDigest {

	private static final int MAX_CHARS = 100_000;
	private static final int HEAD_CHARS = 60_000;
	private static final int TAIL_CHARS = 35_000;
	private static final int ARG_SUMMARY_CHARS = 200;
	private static final int ERROR_SUMMARY_CHARS = 300;
	private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(java.time.ZoneId.systemDefault());

	private TranscriptDigest() {
	}

	/** Capped LLM-prompt digest — see docs/plan/phase-5.3-memory-reflection.md. */
	public static String render(List<EventJournal.JournalEvent> events) {
		StringBuilder sb = new StringBuilder();
		BigDecimal totalCost = BigDecimal.ZERO;
		int numTurns = 0;
		for (EventJournal.JournalEvent event : events) {
			JsonNode p = event.payload();
			switch (event.type()) {
				case "user_message" -> sb.append("User: ").append(p.path("text").asText()).append("\n\n");
				case "assistant_message" -> {
					String text = extractText(p.get("content"));
					if (!text.isBlank()) {
						sb.append("Assistant: ").append(text).append("\n\n");
					}
				}
				case "tool_started" -> sb.append("  → ").append(p.path("name").asText())
						.append("(").append(truncate(compact(p.get("input")), ARG_SUMMARY_CHARS)).append(")\n");
				case "tool_result" -> {
					if (p.path("isError").asBoolean(false)) {
						sb.append("  ✗ tool error: ")
								.append(truncate(p.path("output").asText(""), ERROR_SUMMARY_CHARS)).append("\n");
					}
				}
				case "permission_response" -> {
					if ("deny".equals(p.path("behavior").asText())) {
						sb.append("  ✗ permission denied: ").append(p.path("message").asText("")).append("\n");
					}
				}
				case "error" -> sb.append("! error: ").append(p.path("message").asText("")).append("\n");
				case "warning" -> sb.append("! warning: ").append(p.path("message").asText("")).append("\n");
				case "turn_complete" -> {
					numTurns++;
					if (p.hasNonNull("costUsd")) {
						totalCost = totalCost.add(new BigDecimal(p.get("costUsd").asText("0")));
					}
				}
				default -> { /* skip: ready, system_init, stream_delta, permission_request, state_changed, ... */ }
			}
		}
		sb.append("\n---\n").append(numTurns).append(" turn(s), total cost $").append(totalCost).append('\n');
		return cap(sb.toString());
	}

	/** Uncapped Markdown transcript for the download button — 5.9. */
	public static String renderMarkdown(String sessionName, List<EventJournal.JournalEvent> events) {
		StringBuilder sb = new StringBuilder("# ").append(sessionName).append("\n\n");
		BigDecimal totalCost = BigDecimal.ZERO;
		int numTurns = 0;
		String model = null;
		for (EventJournal.JournalEvent event : events) {
			JsonNode p = event.payload();
			String ts = TS_FORMAT.format(event.ts());
			switch (event.type()) {
				case "user_message" -> sb.append("**User** (").append(ts).append("):\n\n")
						.append(p.path("text").asText()).append("\n\n");
				case "assistant_message" -> {
					String text = extractText(p.get("content"));
					if (!text.isBlank()) {
						sb.append("**Assistant** (").append(ts).append("):\n\n").append(text).append("\n\n");
					}
				}
				case "tool_started" -> sb.append("> 🔧 `").append(p.path("name").asText())
						.append("(").append(truncate(compact(p.get("input")), ARG_SUMMARY_CHARS)).append(")`\n");
				case "tool_result" -> {
					if (p.path("isError").asBoolean(false)) {
						sb.append("> ✗ tool error: ")
								.append(truncate(p.path("output").asText(""), ERROR_SUMMARY_CHARS)).append('\n');
					}
				}
				case "permission_response" -> {
					if ("deny".equals(p.path("behavior").asText())) {
						sb.append("> ✗ permission denied: ").append(p.path("message").asText("")).append('\n');
					}
				}
				case "error" -> sb.append("> ⚠️ error: ").append(p.path("message").asText("")).append('\n');
				case "warning" -> sb.append("> ⚠️ warning: ").append(p.path("message").asText("")).append('\n');
				case "reflection_complete" -> sb.append("> 🧠 reflection applied: ")
						.append(truncate(p.path("episode").asText(""), ERROR_SUMMARY_CHARS)).append('\n');
				case "reflection_discarded" -> sb.append("> 🧠 reflection discarded\n");
				case "turn_complete" -> {
					numTurns++;
					if (p.hasNonNull("model")) {
						model = p.path("model").asText();
					}
					if (p.hasNonNull("costUsd")) {
						totalCost = totalCost.add(new BigDecimal(p.get("costUsd").asText("0")));
					}
				}
				default -> { /* skip: ready, system_init, stream_delta, permission_request, state_changed, ... */ }
			}
		}
		sb.append("\n---\n\n**").append(numTurns).append(" turn(s)");
		if (model != null) {
			sb.append(", last model `").append(model).append('`');
		}
		sb.append(", total cost $").append(totalCost).append("**\n");
		return sb.toString();
	}

	private static String cap(String text) {
		if (text.length() <= MAX_CHARS) {
			return text;
		}
		int omitted = text.length() - HEAD_CHARS - TAIL_CHARS;
		return text.substring(0, HEAD_CHARS) + "\n\n... [" + omitted + " characters omitted] ...\n\n"
				+ text.substring(text.length() - TAIL_CHARS);
	}

	private static String extractText(JsonNode content) {
		if (content == null || !content.isArray()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (JsonNode block : content) {
			if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
				sb.append(block.get("text").asText());
			}
		}
		return sb.toString();
	}

	private static String compact(JsonNode node) {
		return node == null ? "" : node.toString();
	}

	private static String truncate(String s, int max) {
		return s.length() > max ? s.substring(0, max) + "…" : s;
	}
}
