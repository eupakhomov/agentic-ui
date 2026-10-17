package de.pamir.agentic.ui.slack;

import tools.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * One line describing a tool call for a Slack permission prompt. Deliberately a whitelist-ish
 * summary, not a dump of the tool input: a Bash command is shown (that is what the person is
 * approving), a file edit shows the <em>path</em> only — never the content being written — and
 * anything else shows the tool name with a few short scalar arguments. {@code envVars} never
 * appear. The result is masked with {@link SlackRedactor} unless {@code verbose}.
 */
final class SlackToolSummary {

	static final int MAX_CHARS = 500;
	private static final int MAX_VALUE_CHARS = 120;
	private static final int MAX_FIELDS = 4;

	/** Argument names whose values are content or secrets-in-waiting, not worth (or safe) posting. */
	private static final Set<String> CONTENT_KEYS = Set.of(
			"content", "new_string", "old_string", "text", "body", "data", "contents", "file_text", "new_source",
			"edits", "plan", "env", "envvars", "env_vars", "environment", "headers");

	private static final Set<String> PATH_TOOLS = Set.of(
			"Edit", "Write", "MultiEdit", "NotebookEdit", "Read", "Glob", "Grep", "LS");

	private SlackToolSummary() {
	}

	static String summarize(String toolName, JsonNode input, boolean verbose) {
		String raw = describe(toolName, input);
		String shown = verbose ? raw : SlackRedactor.mask(raw);
		return truncate(shown, MAX_CHARS);
	}

	private static String describe(String toolName, JsonNode input) {
		if (input == null || !input.isObject()) {
			return "";
		}
		if ("Bash".equals(toolName)) {
			return input.path("command").asText("");
		}
		if (PATH_TOOLS.contains(toolName)) {
			for (String key : new String[] {"file_path", "notebook_path", "path", "pattern"}) {
				if (input.hasNonNull(key)) {
					return input.get(key).asText();
				}
			}
			return "";
		}
		if ("WebFetch".equals(toolName) && input.hasNonNull("url")) {
			return input.get("url").asText();
		}
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		Iterator<Map.Entry<String, JsonNode>> fields = input.properties().iterator();
		while (fields.hasNext() && shown < MAX_FIELDS) {
			Map.Entry<String, JsonNode> field = fields.next();
			JsonNode value = field.getValue();
			if (!value.isValueNode() || CONTENT_KEYS.contains(field.getKey().toLowerCase())) {
				continue;
			}
			if (shown > 0) {
				sb.append(", ");
			}
			sb.append(field.getKey()).append('=').append(truncate(value.asText(), MAX_VALUE_CHARS));
			shown++;
		}
		return sb.toString();
	}

	static String truncate(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() > max ? s.substring(0, max) + "…" : s;
	}
}
