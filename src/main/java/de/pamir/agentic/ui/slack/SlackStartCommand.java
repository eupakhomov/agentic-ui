package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.git.GitOpsService.PrRef;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What someone asked the bot to do when they mentioned it: {@code @bot review <PR>} or
 * {@code @bot dev <service> <branch> <prompt…>}. Pure parsing — the text is whatever followed the
 * mention, already unescaped from Slack's encoding (so {@code <https://…|label>} is a plain URL).
 * The prompt of a {@code dev} request is everything after the branch, newlines included, so a long
 * multi-line prompt is just the rest of the message.
 */
sealed interface SlackStartCommand {

	/**
	 * @param template the session template to start from ({@code review-with}), or null
	 * @param extra    optional free text after the PR, appended to the built-in review instruction
	 */
	record Review(String template, PrRef pr, String extra) implements SlackStartCommand {
	}

	/** {@code template} is null for plain {@code dev}; {@code prompt} is empty when the message had only a service and a branch. */
	record Dev(String template, String service, String branch, String prompt) implements SlackStartCommand {
	}

	record Help() implements SlackStartCommand {
	}

	record Invalid(String reason) implements SlackStartCommand {
	}

	String USAGE = """
			*Start a session from Slack* (mention me, in this channel):
			• `review <pull request URL>` — starts a review session on the matching local service. Extra text after the URL is added to the review instruction.
			• `dev <service> <branch> <prompt…>` — starts a development session on that service and branch (created if it does not exist). The prompt is everything after the branch and may span several lines; leave it out and I will ask for it in the thread.
			• `review-with <template> <pull request URL>` and `dev-with <template> <service> <branch> <prompt…>` — the same, starting from a session template (put a name with spaces in quotes).""";

	Pattern FIRST_WORD = Pattern.compile("^(\\S+)(?:\\s+([\\s\\S]*))?$");
	Pattern PR_URL = Pattern.compile("^https?://[^/\\s]+/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/pull/(\\d{1,9})(?:[/?#].*)?$");
	Pattern PR_SHORT = Pattern.compile("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)#(\\d{1,9})$");
	Pattern DEV_ARGS = Pattern.compile("^(\\S+)\\s+(\\S+)(?:\\s+([\\s\\S]*))?$");

	static SlackStartCommand parse(String text) {
		String t = text == null ? "" : text.strip();
		Matcher m = FIRST_WORD.matcher(t);
		if (!m.matches()) {
			return new Help();
		}
		String word = m.group(1).toLowerCase(Locale.ROOT);
		String rest = m.group(2) == null ? "" : m.group(2).strip();
		return switch (word) {
			case "help", "?" -> new Help();
			case "review" -> parseReview(null, rest);
			case "dev", "develop", "development" -> parseDev(null, rest);
			case "review-with" -> withTemplate("review-with", rest, SlackStartCommand::parseReview);
			case "dev-with", "develop-with", "development-with" -> withTemplate("dev-with", rest, SlackStartCommand::parseDev);
			default -> new Invalid("I don't know \"" + word + "\".");
		};
	}

	private static SlackStartCommand parseReview(String template, String rest) {
		Matcher m = FIRST_WORD.matcher(rest);
		if (!m.matches()) {
			return new Invalid("`review` needs a pull request, e.g. `review https://github.com/owner/repo/pull/123`.");
		}
		PrRef ref = parsePr(unquote(m.group(1)));
		if (ref == null) {
			return new Invalid("I could not read `" + unquote(m.group(1)) + "` as a pull request. Use the full URL or `owner/repo#123`.");
		}
		return new Review(template, ref, m.group(2) == null ? "" : m.group(2).strip());
	}

	private static SlackStartCommand parseDev(String template, String rest) {
		Matcher m = DEV_ARGS.matcher(rest);
		if (!m.matches()) {
			return new Invalid("`dev` needs a service and a branch (and a prompt), e.g. `dev my-service feat/login Add rate limiting to the login endpoint`.");
		}
		// the prompt may be missing; the handler then asks for it in the thread
		String prompt = m.group(3) == null ? "" : m.group(3).strip();
		return new Dev(template, unquote(m.group(1)), unquote(m.group(2)), prompt);
	}

	/** A template name, or the rest after it. A name with spaces must be quoted (", \u201c\u201d, ' or `). */
	Pattern QUOTED_NAME = Pattern.compile("^(?:\"([^\"]+)\"|\u201c([^\u201d]+)\u201d|`([^`]+)`|'([^']+)')(?:\\s+([\\s\\S]*))?$");

	@FunctionalInterface
	interface WithTemplate {
		SlackStartCommand parse(String template, String rest);
	}

	/** {@code review-with} / {@code dev-with}: the template name comes first, the rest is the plain command's arguments. */
	private static SlackStartCommand withTemplate(String command, String rest, WithTemplate parser) {
		String name;
		String after;
		Matcher quoted = QUOTED_NAME.matcher(rest);
		if (quoted.matches()) {
			name = quoted.group(1) != null ? quoted.group(1) : quoted.group(2) != null ? quoted.group(2)
					: quoted.group(3) != null ? quoted.group(3) : quoted.group(4);
			after = quoted.group(5) == null ? "" : quoted.group(5).strip();
		} else {
			Matcher word = FIRST_WORD.matcher(rest);
			if (!word.matches()) {
				return new Invalid("`" + command + "` needs a template name first, e.g. `"
						+ ("dev-with".equals(command) ? "dev-with bugfix my-service feat/x Fix the login bug" : "review-with strict https://github.com/owner/repo/pull/123") + "`.");
			}
			name = word.group(1);
			after = word.group(2) == null ? "" : word.group(2).strip();
		}
		// a name that is nothing but quote marks (an empty "" or ``) is a missing name
		name = name.replaceAll("^[\"\u201c\u201d'`]+|[\"\u201c\u201d'`]+$", "").strip();
		if (name.isEmpty()) {
			return new Invalid("`" + command + "` needs a template name first.");
		}
		return parser.parse(name, after);
	}

	static PrRef parsePr(String token) {
		Matcher url = PR_URL.matcher(token);
		if (url.matches()) {
			return new PrRef(url.group(1), url.group(2), Integer.parseInt(url.group(3)));
		}
		Matcher shortForm = PR_SHORT.matcher(token);
		if (shortForm.matches()) {
			return new PrRef(shortForm.group(1), shortForm.group(2), Integer.parseInt(shortForm.group(3)));
		}
		return null;
	}

	/** People paste names with Slack code formatting around them. */
	private static String unquote(String token) {
		return token.replaceAll("^`+|`+$", "");
	}
}
