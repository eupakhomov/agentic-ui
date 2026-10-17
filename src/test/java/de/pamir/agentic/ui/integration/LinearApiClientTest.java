package de.pamir.agentic.ui.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link LinearApiClient} against a fake {@code Transport} — no network. */
class LinearApiClientTest {

	private final ObjectMapper mapper = new JsonMapper();
	private final List<String> authHeaders = new ArrayList<>();
	private final List<String> bodies = new ArrayList<>();
	private LinearApiClient.Response next;

	private LinearApiClient client() {
		return new LinearApiClient((auth, body) -> {
			authHeaders.add(auth);
			bodies.add(body);
			if (next == null) {
				throw new IOException("connection refused");
			}
			return next;
		}, "lin_api_key", mapper);
	}

	private void respond(int status, String body) {
		next = new LinearApiClient.Response(status, body);
	}

	@Test
	void listMapsFieldsDropsCompletedAndCanceledAndSendsTheRawKey() {
		respond(200, "{\"data\":{\"viewer\":{\"assignedIssues\":{\"nodes\":["
				+ "{\"identifier\":\"ENG-1\",\"title\":\"A\",\"state\":{\"name\":\"In Progress\",\"type\":\"started\"}},"
				+ "{\"identifier\":\"ENG-2\",\"title\":\"B\",\"state\":{\"name\":\"Done\",\"type\":\"completed\"}},"
				+ "{\"identifier\":\"ENG-3\",\"title\":\"C\",\"state\":{\"name\":\"Canceled\",\"type\":\"canceled\"}},"
				+ "{\"identifier\":\"\",\"title\":\"no id\",\"state\":{\"name\":\"Todo\",\"type\":\"unstarted\"}}]}}}}");

		var list = client().listAssigned();

		assertThat(list).containsExactly(new LinearApiClient.Summary("ENG-1", "A", "In Progress"));
		assertThat(authHeaders).containsExactly("lin_api_key"); // personal key: no "Bearer"
		assertThat(bodies.get(0)).contains("assignedIssues").contains("completed");
	}

	@Test
	void fetchIssueParsesUrlsOrdersCommentsOldestFirstAndMapsLabels() {
		respond(200, "{\"data\":{\"issue\":{\"identifier\":\"ENG-123\",\"title\":\"Fix login\",\"description\":\"desc\","
				+ "\"state\":{\"name\":\"Todo\"},\"labels\":{\"nodes\":[{\"name\":\"bug\"},{\"name\":\"auth\"}]},"
				+ "\"comments\":{\"nodes\":["
				+ "{\"body\":\"second\",\"createdAt\":\"2026-10-02T00:00:00Z\",\"user\":{\"name\":\"Bo\"}},"
				+ "{\"body\":\"first\",\"createdAt\":\"2026-10-01T00:00:00Z\",\"user\":{\"name\":\"Al\"}}]}}}}");

		var issue = client().fetchIssue("https://linear.app/acme/issue/eng-123/fix-login");

		assertThat(bodies.get(0)).contains("\"id\":\"ENG-123\"");
		assertThat(issue.identifier()).isEqualTo("ENG-123");
		assertThat(issue.labels()).containsExactly("bug", "auth");
		assertThat(issue.comments()).extracting(LinearApiClient.Comment::body).containsExactly("first", "second");
	}

	@Test
	void renderCapsDescriptionAndCommentsWithATruncationMarker() {
		var issue = new LinearApiClient.Issue("ENG-1", "T", "Todo", "x".repeat(500), List.of(), List.of(
				new LinearApiClient.Comment("Al", "2026-10-01", "y".repeat(500))));

		String rendered = issue.render(100);

		assertThat(rendered).endsWith("…truncated").contains("Identifier: ENG-1").contains("Title: T");
		assertThat(rendered.length()).isLessThan(300);
		assertThat(new LinearApiClient.Issue("ENG-1", "T", "Todo", "short", List.of(), List.of()).render(100))
				.doesNotContain("truncated");
	}

	@Test
	void unauthorizedIsAnAuthFailureAndServerErrorsAreNot() {
		respond(401, "{}");
		assertThatThrownBy(() -> client().listAssigned()).isInstanceOfSatisfying(
				LinearApiClient.LinearApiException.class, e -> assertThat(e.isAuth()).isTrue());

		respond(503, "oops");
		assertThatThrownBy(() -> client().listAssigned()).isInstanceOfSatisfying(
				LinearApiClient.LinearApiException.class, e -> {
					assertThat(e.isAuth()).isFalse();
					assertThat(e).hasMessageContaining("503");
				});
	}

	@Test
	void graphqlErrorsMissingIssuesAndTransportFailuresAllSurfaceAsApiExceptions() {
		respond(200, "{\"errors\":[{\"message\":\"boom\"}]}");
		assertThatThrownBy(() -> client().listAssigned()).hasMessageContaining("boom");

		respond(200, "{\"data\":{\"issue\":null}}");
		assertThatThrownBy(() -> client().fetchIssue("ENG-9")).hasMessageContaining("not found");

		assertThatThrownBy(() -> client().fetchIssue("no reference here")).hasMessageContaining("not a Linear issue");

		next = null;
		assertThatThrownBy(() -> client().listAssigned()).hasMessageContaining("unreachable");
	}

	@Test
	void configuredReflectsTheKey() {
		assertThat(new LinearApiClient((a, b) -> null, "", mapper).configured()).isFalse();
		assertThat(client().configured()).isTrue();
	}
}
