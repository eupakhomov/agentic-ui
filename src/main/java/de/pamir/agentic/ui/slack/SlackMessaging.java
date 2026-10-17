package de.pamir.agentic.ui.slack;

import tools.jackson.databind.JsonNode;

/**
 * The few Slack Web API calls the mirror needs, as an interface so the outbox and the mirror can
 * be tested against a fake. Every method blocks and throws {@link SlackApiException} on failure.
 */
public interface SlackMessaging {

	/** A message body: plain {@code text} (notification fallback) plus optional Block Kit {@code blocks}. */
	record SlackMessage(String text, JsonNode blocks) {
	}

	/** The bot user's own id (cached after the first call), to recognise a mention of the bot. */
	String botUserId();

	/** Posts a message, into a thread when {@code threadTs} is non-null; returns the new message's {@code ts}. */
	String postMessage(String channel, String threadTs, SlackMessage message);

	/** Replaces a message's text and blocks. */
	void updateMessage(String channel, String ts, SlackMessage message);

	/** Adds an emoji reaction (by name, no colons); an already-present reaction is not an error. */
	void addReaction(String channel, String ts, String name);

	/**
	 * Answers a button click with a message only the clicker sees, via the interaction's one-shot
	 * {@code response_url}. Needs no scope and no channel membership.
	 */
	void respondEphemeral(String responseUrl, String text);

	/** Uploads {@code content} as a text snippet into a thread. Needs the {@code files:write} scope. */
	void uploadSnippet(String channel, String threadTs, String title, String content);
}
