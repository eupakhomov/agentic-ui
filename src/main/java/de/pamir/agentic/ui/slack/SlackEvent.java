package de.pamir.agentic.ui.slack;

import tools.jackson.databind.JsonNode;

/**
 * One deduplicated Socket Mode delivery, already acknowledged to Slack.
 *
 * @param type       envelope type: {@code events_api} (a message) or {@code interactive} (a button click)
 * @param dedupeKey  the key the delivery was deduplicated on, for logging
 * @param payload    the envelope's {@code payload} object, untouched
 */
public record SlackEvent(String type, String dedupeKey, JsonNode payload) {

	@FunctionalInterface
	public interface Listener {
		void onEvent(SlackEvent event);
	}
}
