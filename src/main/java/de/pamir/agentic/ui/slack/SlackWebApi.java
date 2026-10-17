package de.pamir.agentic.ui.slack;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin Slack Web API client on {@link RestClient}, like the Voyage and Linear clients — no Slack
 * SDK. Slack answers HTTP 200 with {@code ok:false} for most failures, so every call funnels through
 * {@link #call} which turns that into a {@link SlackApiException} carrying Slack's error code; a
 * 429 becomes one with {@code retryAfter} set. Tokens travel only in the Authorization header and
 * never appear in an exception message.
 */
@Component
@ConditionalOnProperty(prefix = "artus.slack", name = "enabled", havingValue = "true")
public class SlackWebApi implements SlackMessaging {

	private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(5);

	private final RestClient rest;
	private final ObjectMapper mapper;
	private final SlackProperties props;

	public SlackWebApi(RestClient.Builder builder, ObjectMapper mapper, SlackProperties props) {
		this.rest = builder.baseUrl("https://slack.com/api").build();
		this.mapper = mapper;
		this.props = props;
	}

	// ------------------------------------------------------------------ Socket Mode

	/** Opens a Socket Mode connection; returns the one-shot {@code wss://} URL. */
	String openSocketUrl(String appToken) {
		String url = call("apps.connections.open", appToken, null).path("url").asText("");
		if (url.isBlank()) {
			throw new SlackApiException("apps.connections.open", "missing_url");
		}
		return url;
	}

	/** Validates a bot token; the response names the bot user and team. */
	JsonNode authTest(String botToken) {
		return call("auth.test", botToken, null);
	}

	// ------------------------------------------------------------------ SlackMessaging

	private volatile String botUserId;

	@Override
	public String botUserId() {
		String id = botUserId;
		if (id == null) {
			id = authTest(props.botToken()).path("user_id").asText("");
			if (id.isBlank()) {
				throw new SlackApiException("auth.test", "missing_user_id");
			}
			botUserId = id;
		}
		return id;
	}

	@Override
	public String postMessage(String channel, String threadTs, SlackMessage message) {
		ObjectNode body = messageBody(channel, message);
		if (threadTs != null) {
			body.put("thread_ts", threadTs);
		}
		String ts = call("chat.postMessage", props.botToken(), body).path("ts").asText("");
		if (ts.isBlank()) {
			throw new SlackApiException("chat.postMessage", "missing_ts");
		}
		return ts;
	}

	@Override
	public void updateMessage(String channel, String ts, SlackMessage message) {
		ObjectNode body = messageBody(channel, message);
		body.put("ts", ts);
		call("chat.update", props.botToken(), body);
	}

	@Override
	public void addReaction(String channel, String ts, String name) {
		ObjectNode body = mapper.createObjectNode().put("channel", channel).put("timestamp", ts).put("name", name);
		try {
			call("reactions.add", props.botToken(), body);
		} catch (SlackApiException e) {
			if (!"already_reacted".equals(e.code())) {
				throw e;
			}
		}
	}

	@Override
	public void respondEphemeral(String responseUrl, String text) {
		// the URL comes out of a payload Slack delivered over our authenticated socket, but it is still a
		// URL we are about to POST to: only ever Slack's own hook host
		if (responseUrl == null || !responseUrl.startsWith("https://hooks.slack.com/")) {
			throw new SlackApiException("response_url", "invalid_response_url");
		}
		ObjectNode body = mapper.createObjectNode().put("response_type", "ephemeral").put("replace_original", false)
				.put("text", text);
		try {
			rest.post().uri(URI.create(responseUrl)).contentType(MediaType.APPLICATION_JSON).body(body.toString())
					.retrieve().toBodilessEntity();
		} catch (RestClientResponseException e) {
			throw new SlackApiException("response_url", "http_" + e.getStatusCode().value());
		} catch (RestClientException e) {
			throw new SlackApiException("response_url", "transport_error");
		}
	}

	@Override
	public void uploadSnippet(String channel, String threadTs, String title, String content) {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("filename", title.endsWith(".md") ? title : title + ".md");
		form.add("length", String.valueOf(bytes.length));
		JsonNode slot = call("files.getUploadURLExternal", props.botToken(), form);
		String uploadUrl = slot.path("upload_url").asText("");
		String fileId = slot.path("file_id").asText("");
		if (uploadUrl.isBlank() || fileId.isBlank()) {
			throw new SlackApiException("files.getUploadURLExternal", "missing_upload_url");
		}
		try {
			// the upload URL is pre-signed; no Authorization header goes to it
			rest.post().uri(URI.create(uploadUrl)).contentType(MediaType.APPLICATION_OCTET_STREAM)
					.body(bytes).retrieve().toBodilessEntity();
		} catch (RestClientResponseException e) {
			throw new SlackApiException("file upload", "http_" + e.getStatusCode().value());
		} catch (RestClientException e) {
			throw new SlackApiException("file upload", "transport_error");
		}
		ObjectNode complete = mapper.createObjectNode().put("channel_id", channel);
		if (threadTs != null) {
			complete.put("thread_ts", threadTs);
		}
		complete.putArray("files").addObject().put("id", fileId).put("title", title);
		call("files.completeUploadExternal", props.botToken(), complete);
	}

	// ------------------------------------------------------------------ plumbing

	private ObjectNode messageBody(String channel, SlackMessage message) {
		ObjectNode body = mapper.createObjectNode().put("channel", channel).put("text", message.text());
		if (message.blocks() != null) {
			body.set("blocks", message.blocks());
		}
		body.put("unfurl_links", false).put("unfurl_media", false);
		return body;
	}

	/** {@code body} is an {@link ObjectNode} (sent as JSON), a form map (sent urlencoded), or null (empty form). */
	private JsonNode call(String method, String token, Object body) {
		JsonNode response;
		try {
			RestClient.RequestBodySpec request = rest.post().uri("/" + method)
					.header("Authorization", "Bearer " + token);
			if (body instanceof ObjectNode json) {
				request = request.contentType(MediaType.APPLICATION_JSON).body(json.toString());
			} else if (body instanceof MultiValueMap<?, ?> form) {
				request = request.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form);
			} else {
				request = request.contentType(MediaType.APPLICATION_FORM_URLENCODED);
			}
			response = request.retrieve().body(JsonNode.class);
		} catch (RestClientResponseException e) {
			if (e.getStatusCode().value() == 429) {
				throw new SlackApiException(method, "ratelimited", retryAfter(e));
			}
			throw new SlackApiException(method, "http_" + e.getStatusCode().value());
		} catch (RestClientException e) {
			throw new SlackApiException(method, "transport_error");
		}
		if (response == null || !response.path("ok").asBoolean(false)) {
			String code = response == null ? "empty_response" : response.path("error").asText("unknown_error");
			throw new SlackApiException(method, code, "ratelimited".equals(code) ? DEFAULT_RETRY_AFTER : null,
					response == null ? null : detail(response));
		}
		return response;
	}

	/** Slack's validation notes (never contain tokens), e.g. "must be less than 3001 characters [json-pointer:/blocks/1/text/text]". */
	private static String detail(JsonNode response) {
		StringBuilder sb = new StringBuilder();
		for (JsonNode m : response.path("response_metadata").path("messages")) {
			sb.append(sb.length() > 0 ? "; " : "").append(m.asText());
		}
		return sb.length() > 600 ? sb.substring(0, 600) + "…" : sb.toString();
	}

	private static Duration retryAfter(RestClientResponseException e) {
		String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
		try {
			return header == null ? DEFAULT_RETRY_AFTER : Duration.ofSeconds(Math.max(1, Long.parseLong(header.strip())));
		} catch (NumberFormatException ex) {
			return DEFAULT_RETRY_AFTER;
		}
	}
}
