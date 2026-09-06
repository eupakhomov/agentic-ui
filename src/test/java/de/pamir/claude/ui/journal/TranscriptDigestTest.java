package de.pamir.claude.ui.journal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure event-list rendering, no Spring/DB — see docs/plan/phase-9-production-hardening.md T1. */
class TranscriptDigestTest {

	private final JsonMapper mapper = new JsonMapper();
	private final Instant t0 = Instant.parse("2026-09-06T10:00:00Z");

	@Test
	void rendersUserAssistantToolAndTurnEventsWithACostFooter() {
		ObjectNode userMsg = mapper.createObjectNode().put("text", "Fix the bug");

		ObjectNode assistantContent = mapper.createObjectNode();
		ArrayNode content = mapper.createArrayNode();
		content.add(mapper.createObjectNode().put("type", "text").put("text", "I'll look into it."));
		assistantContent.set("content", content);

		ObjectNode toolStart = mapper.createObjectNode().put("name", "Bash");
		toolStart.set("input", mapper.createObjectNode().put("command", "ls"));

		ObjectNode toolErr = mapper.createObjectNode().put("isError", true).put("output", "boom");

		ObjectNode denied = mapper.createObjectNode().put("behavior", "deny").put("message", "no");

		ObjectNode turnComplete = mapper.createObjectNode().put("costUsd", "0.05");

		List<EventJournal.JournalEvent> events = List.of(
				new EventJournal.JournalEvent(1, t0, "user_message", userMsg),
				new EventJournal.JournalEvent(2, t0, "assistant_message", assistantContent),
				new EventJournal.JournalEvent(3, t0, "tool_started", toolStart),
				new EventJournal.JournalEvent(4, t0, "tool_result", toolErr),
				new EventJournal.JournalEvent(5, t0, "permission_response", denied),
				new EventJournal.JournalEvent(6, t0, "turn_complete", turnComplete));

		String digest = TranscriptDigest.render(events);

		assertThat(digest).contains("User: Fix the bug");
		assertThat(digest).contains("Assistant: I'll look into it.");
		assertThat(digest).contains("Bash(");
		assertThat(digest).contains("tool error: boom");
		assertThat(digest).contains("permission denied: no");
		assertThat(digest).contains("1 turn(s), total cost $0.05");
	}

	@Test
	void skipsEventTypesThatCarryNoDigestSignal() {
		List<EventJournal.JournalEvent> events = List.of(
				new EventJournal.JournalEvent(1, t0, "ready", mapper.createObjectNode()),
				new EventJournal.JournalEvent(2, t0, "state_changed", mapper.createObjectNode()));

		String digest = TranscriptDigest.render(events);

		assertThat(digest).contains("0 turn(s), total cost $0");
	}

	@Test
	void capsAVeryLongDigestKeepingHeadAndTail() {
		String hugeText = "x".repeat(150_000);
		ObjectNode payload = mapper.createObjectNode().put("text", hugeText);
		List<EventJournal.JournalEvent> events = List.of(new EventJournal.JournalEvent(1, t0, "user_message", payload));

		String digest = TranscriptDigest.render(events);

		assertThat(digest).contains("characters omitted");
		assertThat(digest.length()).isLessThan(hugeText.length());
	}

	@Test
	void renderMarkdownIncludesATitleHeaderModelAndCostFooter() {
		ObjectNode userMsg = mapper.createObjectNode().put("text", "Do the thing");
		ObjectNode turnComplete = mapper.createObjectNode().put("model", "claude-sonnet-5").put("costUsd", "0.10");

		List<EventJournal.JournalEvent> events = List.of(
				new EventJournal.JournalEvent(1, t0, "user_message", userMsg),
				new EventJournal.JournalEvent(2, t0, "turn_complete", turnComplete));

		String markdown = TranscriptDigest.renderMarkdown("My Session", events);

		assertThat(markdown).startsWith("# My Session");
		assertThat(markdown).contains("**User**");
		assertThat(markdown).contains("last model `claude-sonnet-5`");
		assertThat(markdown).contains("total cost $0.10");
	}
}
