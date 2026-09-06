package de.pamir.claude.ui.library;

import de.pamir.claude.ui.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * docs/plan/phase-9-production-hardening.md O5: a present-but-invalid Voyage key should fail
 * with a diagnosable message (not a bare "4xx ... <html>"), so the warning tryEmbed() already
 * logs is actually actionable.
 */
class VoyageEmbeddingClientTest {

	private static AppProperties propsWithKey(String key) {
		return new AppProperties("/repo", "/worktrees", "/skills", "/memory", 4, "authtoken", "", key, "logs", 30,
				65536, 1048576, Map.of());
	}

	private VoyageEmbeddingClient client(AppProperties props, MockRestServiceServer[] serverOut) {
		RestClient.Builder builder = RestClient.builder();
		serverOut[0] = MockRestServiceServer.bindTo(builder).build();
		return new VoyageEmbeddingClient(props, builder);
	}

	@Test
	void embedReturnsVectorOnSuccess() {
		var serverOut = new MockRestServiceServer[1];
		var client = client(propsWithKey("good-key"), serverOut);
		serverOut[0].expect(requestTo("https://api.voyageai.com/v1/embeddings"))
				.andRespond(withSuccess("""
						{"data": [{"embedding": [0.1, 0.2, 0.3]}]}""", MediaType.APPLICATION_JSON));

		float[] result = client.embed("hello", true);

		assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
	}

	@Test
	void embedThrowsADiagnosableMessageOn401() {
		var serverOut = new MockRestServiceServer[1];
		var client = client(propsWithKey("bad-key"), serverOut);
		serverOut[0].expect(requestTo("https://api.voyageai.com/v1/embeddings"))
				.andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
						.body("{\"error\": \"invalid api key\"}").contentType(MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.embed("hello", true))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Voyage API key rejected")
				.hasMessageContaining("401")
				.hasMessageContaining("CLAUDE_UI_VOYAGE_API_KEY");
	}

	@Test
	void embedThrowsADiagnosableMessageOn403() {
		var serverOut = new MockRestServiceServer[1];
		var client = client(propsWithKey("bad-key"), serverOut);
		serverOut[0].expect(requestTo("https://api.voyageai.com/v1/embeddings"))
				.andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN).body("forbidden"));

		assertThatThrownBy(() -> client.embed("hello", true))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Voyage API key rejected")
				.hasMessageContaining("403");
	}

	@Test
	void embedThrowsAGenericMessageOnOtherServerErrors() {
		var serverOut = new MockRestServiceServer[1];
		var client = client(propsWithKey("good-key"), serverOut);
		serverOut[0].expect(requestTo("https://api.voyageai.com/v1/embeddings"))
				.andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).body("oops"));

		assertThatThrownBy(() -> client.embed("hello", true))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Voyage embeddings request failed")
				.hasMessageContaining("500")
				.hasMessageNotContaining("API key rejected");
	}

	@Test
	void embedThrowsWhenUnconfigured() {
		var serverOut = new MockRestServiceServer[1];
		var client = client(propsWithKey(""), serverOut);

		assertThatThrownBy(() -> client.embed("hello", true))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not configured");
	}
}
