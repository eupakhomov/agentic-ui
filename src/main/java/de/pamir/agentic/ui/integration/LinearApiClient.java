package de.pamir.agentic.ui.integration;

import de.pamir.agentic.ui.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct Linear GraphQL access for API-key mode (phase 19): the ticket list and an issue with its
 * comments, without an LLM turn or MCP round trips. Used by {@link TicketImportService} only; any
 * failure surfaces as {@link LinearApiException} and the caller falls back to the system-turn path.
 * The key never leaves this process (it is the same env-var value the Linear MCP server block uses).
 */
@Component
public class LinearApiClient {

	static final String ENDPOINT = "https://api.linear.app/graphql";
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final Pattern IDENTIFIER = Pattern.compile("([A-Za-z][A-Za-z0-9]*-\\d+)");

	private static final String LIST_QUERY = "query { viewer { assignedIssues(first: 20, orderBy: updatedAt, "
			+ "filter: { state: { type: { nin: [\"completed\", \"canceled\"] } } }) "
			+ "{ nodes { identifier title state { name type } } } } }";
	private static final String ISSUE_QUERY = "query($id: String!) { issue(id: $id) { identifier title description "
			+ "state { name } labels { nodes { name } } "
			+ "comments(first: 100) { nodes { body createdAt user { name } } } } }";

	/** HTTP seam: POSTs a GraphQL body with the given {@code Authorization} value. */
	@FunctionalInterface
	interface Transport {
		Response post(String authorization, String body) throws IOException, InterruptedException;
	}

	record Response(int status, String body) {
	}

	/** {@code auth}: true for a rejected key (401/403) — worth telling the user to check it. */
	public static class LinearApiException extends RuntimeException {
		private final boolean auth;

		LinearApiException(String message, boolean auth) {
			super(message);
			this.auth = auth;
		}

		public boolean isAuth() {
			return auth;
		}
	}

	public record Summary(String identifier, String title, String state) {
	}

	public record Comment(String author, String createdAt, String body) {
	}

	public record Issue(String identifier, String title, String state, String description,
						List<String> labels, List<Comment> comments) {

		/** Plain-text rendering for the import prompt; description + comments capped at {@code maxChars}. */
		public String render(int maxChars) {
			StringBuilder sb = new StringBuilder();
			sb.append("Identifier: ").append(identifier).append('\n');
			sb.append("Title: ").append(title).append('\n');
			sb.append("State: ").append(state).append('\n');
			if (!labels.isEmpty()) {
				sb.append("Labels: ").append(String.join(", ", labels)).append('\n');
			}
			StringBuilder body = new StringBuilder();
			body.append("\nDescription:\n").append(description == null || description.isBlank() ? "(none)" : description.strip())
					.append('\n');
			if (!comments.isEmpty()) {
				body.append("\nComments (oldest first):\n");
				for (Comment c : comments) {
					body.append("- ").append(c.author()).append(" (").append(c.createdAt()).append("): ")
							.append(c.body().strip()).append('\n');
				}
			}
			if (body.length() > maxChars) {
				body.setLength(maxChars);
				body.append("\n…truncated");
			}
			return sb.append(body).toString();
		}
	}

	private final Transport transport;
	private final String apiKey;
	private final ObjectMapper mapper;

	@Autowired
	public LinearApiClient(AppProperties props, ObjectMapper mapper) {
		this(httpTransport(), props.linearApiKey(), mapper);
	}

	LinearApiClient(Transport transport, String apiKey, ObjectMapper mapper) {
		this.transport = transport;
		this.apiKey = apiKey;
		this.mapper = mapper;
	}

	/** True when an API key is set — OAuth-only setups have no usable credential outside the claude CLI. */
	public boolean configured() {
		return apiKey != null && !apiKey.isBlank();
	}

	public List<Summary> listAssigned() {
		JsonNode nodes = query(LIST_QUERY, null).path("viewer").path("assignedIssues").path("nodes");
		List<Summary> out = new ArrayList<>();
		for (JsonNode n : nodes) {
			String type = n.path("state").path("type").asText("");
			if (type.equals("completed") || type.equals("canceled")) {
				continue; // server-side filter should already exclude these; belt and braces
			}
			String id = n.path("identifier").asText("").strip();
			String title = n.path("title").asText("").strip();
			if (id.isBlank() || title.isBlank()) {
				continue;
			}
			out.add(new Summary(id, title, n.path("state").path("name").asText("").strip()));
		}
		return out;
	}

	/** @param ref a short identifier (ENG-123) or a Linear issue URL */
	public Issue fetchIssue(String ref) {
		Matcher m = IDENTIFIER.matcher(ref.strip());
		if (!m.find()) {
			throw new LinearApiException("not a Linear issue reference: " + ref, false);
		}
		JsonNode issue = query(ISSUE_QUERY, mapper.createObjectNode().put("id", m.group(1).toUpperCase())).path("issue");
		if (issue.isMissingNode() || issue.isNull()) {
			throw new LinearApiException("issue not found: " + m.group(1), false);
		}
		List<String> labels = new ArrayList<>();
		issue.path("labels").path("nodes").forEach(l -> labels.add(l.path("name").asText()));
		List<Comment> comments = new ArrayList<>();
		issue.path("comments").path("nodes").forEach(c -> comments.add(new Comment(
				c.path("user").path("name").asText("unknown"), c.path("createdAt").asText(""), c.path("body").asText(""))));
		comments.sort(Comparator.comparing(Comment::createdAt)); // ISO-8601, lexicographic = chronological
		return new Issue(issue.path("identifier").asText(m.group(1).toUpperCase()), issue.path("title").asText(""),
				issue.path("state").path("name").asText(""), issue.path("description").asText(""), labels, comments);
	}

	private JsonNode query(String query, JsonNode variables) {
		var payload = mapper.createObjectNode().put("query", query);
		if (variables != null) {
			payload.set("variables", variables);
		}
		Response response;
		try {
			response = transport.post(apiKey, payload.toString());
		} catch (IOException e) {
			throw new LinearApiException("Linear API unreachable: " + e.getMessage(), false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LinearApiException("interrupted calling the Linear API", false);
		}
		if (response.status() == 401 || response.status() == 403) {
			throw new LinearApiException("Linear API rejected the key (HTTP " + response.status() + ")", true);
		}
		if (response.status() / 100 != 2) {
			throw new LinearApiException("Linear API returned HTTP " + response.status(), false);
		}
		JsonNode root;
		try {
			root = mapper.readTree(response.body());
		} catch (RuntimeException e) {
			throw new LinearApiException("Linear API returned unparseable JSON", false);
		}
		if (root.path("errors").isArray() && !root.path("errors").isEmpty()) {
			String msg = root.path("errors").get(0).path("message").asText("unknown error");
			boolean auth = root.path("errors").get(0).path("extensions").path("type").asText("").contains("authentication");
			throw new LinearApiException("Linear API error: " + msg, auth);
		}
		return root.path("data");
	}

	private static Transport httpTransport() {
		HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
		return (authorization, body) -> {
			HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT)).timeout(TIMEOUT)
					.header("Content-Type", "application/json").header("Authorization", authorization)
					.POST(HttpRequest.BodyPublishers.ofString(body)).build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			return new Response(response.statusCode(), response.body());
		};
	}
}
