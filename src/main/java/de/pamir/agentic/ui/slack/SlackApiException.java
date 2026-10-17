package de.pamir.agentic.ui.slack;

import java.time.Duration;
import java.util.Set;

/**
 * A failed Slack Web API call. {@link #code()} is Slack's own error string ({@code not_in_channel},
 * {@code invalid_auth}, …) or one of ours for transport problems ({@code transport_error},
 * {@code http_503}). {@link #retryAfter()} is set for a 429. The message never contains a token.
 */
public class SlackApiException extends RuntimeException {

	/** Slack error codes that are worth retrying; everything else is a permanent refusal. */
	private static final Set<String> TRANSIENT_CODES = Set.of(
			"ratelimited", "rate_limited", "internal_error", "fatal_error", "service_unavailable",
			"request_timeout", "transport_error");

	private final String code;
	private final Duration retryAfter;

	public SlackApiException(String method, String code) {
		this(method, code, null);
	}

	public SlackApiException(String method, String code, Duration retryAfter) {
		this(method, code, retryAfter, null);
	}

	/** {@code detail}: Slack's own explanation ({@code response_metadata.messages}), e.g. which block was invalid. */
	public SlackApiException(String method, String code, Duration retryAfter, String detail) {
		super("slack " + method + " failed: " + code + (detail == null || detail.isBlank() ? "" : " (" + detail + ")"));
		this.code = code;
		this.retryAfter = retryAfter;
	}

	public String code() {
		return code;
	}

	/** How long Slack asked us to wait (429 {@code Retry-After}), or null. */
	public Duration retryAfter() {
		return retryAfter;
	}

	/** Worth another attempt: rate-limited, a 5xx, a network failure, or a Slack-side hiccup. */
	public boolean transientError() {
		return retryAfter != null || TRANSIENT_CODES.contains(code) || code.startsWith("http_5");
	}
}
