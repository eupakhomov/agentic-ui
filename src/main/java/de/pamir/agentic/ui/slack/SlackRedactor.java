package de.pamir.agentic.ui.slack;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort masking of secrets in text that is about to leave the machine (a Bash command in a
 * permission prompt, a tool error, an assistant excerpt). Known token shapes are replaced
 * wholesale; {@code NAME=value} / {@code "name": "value"} pairs whose name ends in a secret-ish
 * word keep the name and lose the value, so the reader can still see <em>what</em> was set.
 *
 * <p>This is a seatbelt, not a guarantee — an arbitrary secret with no recognisable shape or name
 * passes through. That is why Slack mirroring is opt-in per session and why file contents and
 * {@code envVars} are never posted at all (see {@link SlackToolSummary}).
 */
final class SlackRedactor {

	private static final String MASK = "***";

	private record Rule(Pattern pattern, String replacement) {
	}

	private static final List<Rule> RULES = List.of(
			new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"),
					"[private key]"),
			new Rule(Pattern.compile("\\bxox[abprsc]-[A-Za-z0-9-]{10,}"), "xox*-" + MASK),
			new Rule(Pattern.compile("\\bxapp-[A-Za-z0-9-]{10,}"), "xapp-" + MASK),
			new Rule(Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"), "gh*_" + MASK),
			new Rule(Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}"), "github_pat_" + MASK),
			new Rule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"), "sk-" + MASK),
			new Rule(Pattern.compile("\\blin_api_[A-Za-z0-9]{20,}"), "lin_api_" + MASK),
			new Rule(Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"), "AKIA" + MASK),
			new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), "[jwt]"),
			// "Authorization: Bearer abc", -H 'Authorization: abc', authorization="Basic abc"
			new Rule(Pattern.compile("(?i)(authorization[\"']?\\s*[:=]\\s*[\"']?(?:bearer|basic|token)?\\s*)[^\\s\"']+"),
					"$1" + MASK),
			new Rule(Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}"), "Bearer " + MASK),
			// https://user:password@host
			new Rule(Pattern.compile("(?<=://)[^\\s/:@]+:[^\\s/@]+(?=@)"), MASK + ":" + MASK),
			// --password hunter2, --token=abc, -p style is deliberately not matched (too ambiguous)
			new Rule(Pattern.compile("(?i)(--?(?:password|passwd|token|secret|api-?key|access-?key)[= ]\\s*)[^\\s\"']+"),
					"$1" + MASK),
			// TOKEN=abc, db_password: "abc", "apiKey": "abc" — the name must END in the keyword
			// (so max_tokens: 4096 is left alone)
			new Rule(Pattern.compile("(?i)\\b([A-Za-z0-9_.-]*(?:token|secret|password|passwd|api_?key|access_?key"
					+ "|private_?key|credentials?))([\"']?\\s*[=:]\\s*)([\"']?)[^\\s\"',}]+"), "$1$2$3" + MASK));

	private SlackRedactor() {
	}

	static String mask(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		String out = text;
		for (Rule rule : RULES) {
			Matcher m = rule.pattern().matcher(out);
			out = m.replaceAll(rule.replacement());
		}
		return out;
	}
}
