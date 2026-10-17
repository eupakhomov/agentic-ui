package de.pamir.agentic.ui.slack;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import de.pamir.agentic.ui.slack.SlackMessaging.SlackMessage;

/**
 * Block Kit JSON for every message the mirror posts. Pure functions of their arguments — no state,
 * no I/O — so the exact payloads can be unit-tested. Interaction ids are part of the contract with
 * the inbound side (phase 18 Step 3): {@code block_id} carries the session (and, for prompts, the
 * request) so a click can be routed without server-side state; {@code action_id} says what was
 * pressed; {@code value} carries the request id or the chosen option.
 */
final class SlackBlocks {

	// action ids
	static final String SESSION_STOP = "session_stop";
	static final String SESSION_STATUS = "session_status";
	static final String SESSION_CLOSE = "session_close";
	static final String SESSION_OPEN = "session_open";
	static final String PERM_ALLOW = "perm_allow";
	static final String PERM_DENY = "perm_deny";
	static final String PLAN_APPROVE = "plan_approve";
	static final String PLAN_KEEP = "plan_keep";
	/** Prefix: Slack requires unique action ids within a message, so option buttons are {@code ask_option_1}, {@code ask_option_2}, …;
	 * the single option select is plain {@code ask_option}. Match with {@code startsWith}. */
	static final String ASK_OPTION = "ask_option";

	// block id prefixes: "<prefix>:<sessionId>[:<requestId>]"
	static final String BLOCK_SESSION = "session";
	static final String BLOCK_PERM = "perm";
	static final String BLOCK_PLAN = "plan";
	static final String BLOCK_ASK = "ask";

	static final int SECTION_MAX = 2900;        // Slack's limit is 3000 chars of mrkdwn per section
	static final int PLAN_INLINE_MAX = 12_000;  // longer plans: first chunk inline + the rest as a snippet
	private static final int BUTTON_TEXT_MAX = 75;
	private static final int MAX_BUTTONS = 5;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SlackBlocks() {
	}

	/** What the parent (top-level) message shows; built by the mirror from the session and its events. */
	record ParentView(String sessionId, String name, String stateLabel, String contextLine, String prLine,
					  String kickoffExcerpt, String openUrl, boolean controls) {
	}

	record PlanMessage(SlackMessage message, boolean needsSnippet) {
	}

	// ------------------------------------------------------------------ parent

	static SlackMessage parent(ParentView v) {
		ArrayNode blocks = MAPPER.createArrayNode();
		blocks.add(section("*" + esc(v.name()) + "*   " + esc(v.stateLabel())));
		blocks.add(context(esc(v.contextLine())));
		if (v.prLine() != null && !v.prLine().isBlank()) {
			blocks.add(context(v.prLine()));
		}
		if (v.kickoffExcerpt() != null && !v.kickoffExcerpt().isBlank()) {
			blocks.add(section("> " + esc(v.kickoffExcerpt()).replace("\n", "\n> ")));
		}
		if (v.controls()) {
			ArrayNode elements = MAPPER.createArrayNode();
			ObjectNode stop = button(SESSION_STOP, "Stop", v.sessionId(), "danger", null);
			stop.set("confirm", confirm("Stop this session?", "The current turn is interrupted. The session stays open.",
					"Stop"));
			elements.add(stop);
			elements.add(button(SESSION_STATUS, "Status", v.sessionId(), null, null));
			ObjectNode close = button(SESSION_CLOSE, "Close", v.sessionId(), null, null);
			close.set("confirm", confirm("Close this session?",
					"Its worktree is removed and it cannot be reopened. The branch is kept. Nothing is closed if there are uncommitted changes.", "Close"));
			elements.add(close);
			if (v.openUrl() != null && !v.openUrl().isBlank()) {
				elements.add(button(SESSION_OPEN, "Open", v.sessionId(), null, v.openUrl()));
			}
			blocks.add(actions(BLOCK_SESSION + ":" + v.sessionId(), elements));
		}
		return new SlackMessage(v.name() + " — " + v.stateLabel(), blocks);
	}

	// ------------------------------------------------------------------ prompts

	static SlackMessage permission(String sessionId, String requestId, String toolName, String summary,
								   String sessionName) {
		ArrayNode blocks = MAPPER.createArrayNode();
		blocks.add(section("*Permission needed* — `" + esc(toolName) + "`"
				+ (summary.isBlank() ? "" : "\n```" + codeEsc(summary) + "```")));
		blocks.add(decisionButtons(BLOCK_PERM, sessionId, requestId, PERM_ALLOW, "Allow", PERM_DENY, "Deny"));
		return new SlackMessage(sessionName + ": permission needed for " + toolName, blocks);
	}

	/** Chunks the plan into sections; a very long plan keeps only its first chunk inline. */
	static PlanMessage plan(String sessionId, String requestId, String planText, String sessionName) {
		List<String> chunks = chunk(toMrkdwn(planText), SECTION_MAX);
		boolean needsSnippet = planText.length() > PLAN_INLINE_MAX;
		ArrayNode blocks = MAPPER.createArrayNode();
		blocks.add(section("*Plan ready for approval*"));
		if (chunks.isEmpty()) {
			blocks.add(section("_(empty plan)_"));
		} else if (needsSnippet) {
			blocks.add(section(chunks.get(0)));
			blocks.add(section("_The full plan is too long to show here — it follows as a file._"));
		} else {
			chunks.forEach(c -> blocks.add(section(c)));
		}
		blocks.add(decisionButtons(BLOCK_PLAN, sessionId, requestId, PLAN_APPROVE, "Approve plan",
				PLAN_KEEP, "Keep planning"));
		return new PlanMessage(new SlackMessage(sessionName + ": plan ready for approval", blocks), needsSnippet);
	}

	/**
	 * A single-question, single-choice {@code AskUserQuestion}: one button per option (a select
	 * past five). Anything else (several questions, multi-select) is shown as text with a pointer
	 * to the dashboard — the free-text thread reply still answers it.
	 */
	static SlackMessage question(String sessionId, String requestId, JsonNode input, String sessionName) {
		JsonNode questions = input == null ? null : input.path("questions");
		JsonNode first = questions != null && questions.isArray() && !questions.isEmpty() ? questions.get(0) : null;
		String text = first == null ? "The agent has a question." : first.path("question").asText("The agent has a question.");
		ArrayNode blocks = MAPPER.createArrayNode();
		String header = first != null && first.hasNonNull("header") ? "*" + esc(first.get("header").asText()) + "*\n" : "";
		blocks.add(section(header + esc(text)));
		JsonNode options = first == null ? null : first.path("options");
		boolean simple = first != null && questions.size() == 1 && !first.path("multiSelect").asBoolean(false)
				&& options != null && options.isArray() && !options.isEmpty();
		if (!simple) {
			blocks.add(context("Answer on the dashboard, or reply in this thread."));
		} else {
			String blockId = BLOCK_ASK + ":" + sessionId + ":" + requestId;
			List<String> labels = new ArrayList<>();
			for (JsonNode option : options) {
				labels.add(option.path("label").asText(""));
			}
			blocks.add(optionDescriptions(options));
			if (labels.size() <= MAX_BUTTONS) {
				ArrayNode elements = MAPPER.createArrayNode();
				for (int i = 0; i < labels.size(); i++) {
					String l = labels.get(i);
					elements.add(button(ASK_OPTION + "_" + (i + 1), SlackToolSummary.truncate(l, BUTTON_TEXT_MAX), l, null, null));
				}
				blocks.add(actions(blockId, elements));
			} else {
				ObjectNode select = MAPPER.createObjectNode().put("type", "static_select").put("action_id", ASK_OPTION);
				select.set("placeholder", plain("Choose an option"));
				ArrayNode opts = select.putArray("options");
				labels.forEach(l -> opts.add(MAPPER.createObjectNode()
						.<ObjectNode>set("text", plain(SlackToolSummary.truncate(l, BUTTON_TEXT_MAX)))
						.put("value", SlackToolSummary.truncate(l, BUTTON_TEXT_MAX))));
				blocks.add(actions(blockId, MAPPER.createArrayNode().add(select)));
			}
			blocks.add(context("Or reply in this thread with your own answer."));
		}
		return new SlackMessage(sessionName + ": question — " + SlackToolSummary.truncate(text, 80), blocks);
	}

	private static ObjectNode optionDescriptions(JsonNode options) {
		StringBuilder sb = new StringBuilder();
		for (JsonNode option : options) {
			String description = option.path("description").asText("");
			if (!description.isBlank()) {
				sb.append("• *").append(esc(option.path("label").asText(""))).append("* — ")
						.append(esc(SlackToolSummary.truncate(description, 200))).append('\n');
			}
		}
		return sb.length() == 0 ? context(" ") : section(sb.toString().stripTrailing());
	}

	// ------------------------------------------------------------------ notices

	/** A one-section message; {@code mrkdwn} must already be escaped/formatted by the caller. */
	static SlackMessage notice(String fallbackText, String mrkdwn) {
		ArrayNode blocks = MAPPER.createArrayNode();
		blocks.add(section(SlackToolSummary.truncate(mrkdwn, SECTION_MAX)));
		return new SlackMessage(fallbackText, blocks);
	}

	/** The same prompt after it was settled (on the dashboard or in Slack): buttons gone, outcome shown. */
	static SlackMessage settled(String title, String outcome) {
		return settled(title, null, outcome);
	}

	/** As above, keeping the (already masked) one-line {@code detail} — e.g. the command — so the thread still reads as a log. */
	static SlackMessage settled(String title, String detail, String outcome) {
		ArrayNode blocks = MAPPER.createArrayNode();
		blocks.add(section(detail == null || detail.isBlank() ? title : title + "\n```" + codeEsc(detail) + "```"));
		blocks.add(context(outcome));
		return new SlackMessage(title, blocks);
	}

	/** Prepends {@code prefixMrkdwn} to the first section's text — used to tag a child session's messages. */
	static SlackMessage withPrefix(SlackMessage message, String prefixMrkdwn) {
		JsonNode blocks = message.blocks();
		if (blocks != null && blocks.isArray() && !blocks.isEmpty()) {
			JsonNode text = blocks.get(0).path("text");
			if (text instanceof ObjectNode textNode && textNode.hasNonNull("text")) {
				textNode.put("text", prefixMrkdwn + textNode.get("text").asText());
			}
		}
		return new SlackMessage(message.text(), blocks);
	}

	// ------------------------------------------------------------------ text helpers

	/** Slack control characters in user/agent text. */
	static String esc(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Inside a code fence only the control characters matter, and a stray fence must not end it early. */
	private static String codeEsc(String s) {
		return esc(s).replace("```", "'''");
	}

	private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
	private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+(.*)$");
	private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)");

	/** Claude-flavoured Markdown to Slack mrkdwn: bold, headings as bold lines, links. */
	static String toMrkdwn(String markdown) {
		String s = esc(markdown == null ? "" : markdown);
		s = BOLD.matcher(s).replaceAll("*$1*");
		s = HEADING.matcher(s).replaceAll("*$1*");
		s = LINK.matcher(s).replaceAll("<$2|$1>");
		return s;
	}

	/** Splits on blank lines (then lines, then hard) so no chunk exceeds {@code max}. */
	static List<String> chunk(String text, int max) {
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String paragraph : text.strip().split("\n\n")) {
			for (String piece : splitHard(paragraph, max)) {
				if (current.length() > 0 && current.length() + piece.length() + 2 > max) {
					chunks.add(current.toString());
					current.setLength(0);
				}
				if (current.length() > 0) {
					current.append("\n\n");
				}
				current.append(piece);
			}
		}
		if (current.length() > 0) {
			chunks.add(current.toString());
		}
		return chunks;
	}

	private static List<String> splitHard(String s, int max) {
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < s.length(); i += max) {
			parts.add(s.substring(i, Math.min(s.length(), i + max)));
		}
		if (parts.isEmpty()) {
			parts.add("");
		}
		return parts;
	}

	// ------------------------------------------------------------------ Block Kit primitives

	private static ObjectNode section(String mrkdwn) {
		ObjectNode block = MAPPER.createObjectNode().put("type", "section");
		block.putObject("text").put("type", "mrkdwn").put("text", mrkdwn);
		return block;
	}

	private static ObjectNode context(String mrkdwn) {
		ObjectNode block = MAPPER.createObjectNode().put("type", "context");
		block.putArray("elements").addObject().put("type", "mrkdwn").put("text", mrkdwn);
		return block;
	}

	private static ObjectNode plain(String text) {
		return MAPPER.createObjectNode().put("type", "plain_text").put("text", text).put("emoji", false);
	}

	private static ObjectNode actions(String blockId, ArrayNode elements) {
		ObjectNode block = MAPPER.createObjectNode().put("type", "actions").put("block_id", blockId);
		block.set("elements", elements);
		return block;
	}

	private static ObjectNode button(String actionId, String text, String value, String style, String url) {
		ObjectNode b = MAPPER.createObjectNode().put("type", "button").put("action_id", actionId);
		b.set("text", plain(text));
		b.put("value", value);
		if (style != null) {
			b.put("style", style);
		}
		if (url != null) {
			b.put("url", url);
		}
		return b;
	}

	private static ObjectNode confirm(String title, String text, String confirmLabel) {
		ObjectNode c = MAPPER.createObjectNode();
		c.set("title", plain(title));
		c.set("text", plain(text));
		c.set("confirm", plain(confirmLabel));
		c.set("deny", plain("Cancel"));
		return c;
	}

	private static ObjectNode decisionButtons(String blockPrefix, String sessionId, String requestId,
											  String yesAction, String yesText, String noAction, String noText) {
		ArrayNode elements = MAPPER.createArrayNode();
		elements.add(button(yesAction, yesText, requestId, "primary", null));
		elements.add(button(noAction, noText, requestId, "danger", null));
		return actions(blockPrefix + ":" + sessionId + ":" + requestId, elements);
	}
}
