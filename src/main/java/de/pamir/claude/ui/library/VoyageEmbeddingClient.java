package de.pamir.claude.ui.library;

import de.pamir.claude.ui.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Voyage AI embeddings over plain REST. The API key is a secret and therefore env-only
 * (CLAUDE_UI_VOYAGE_API_KEY), mirroring the Linear key; without it the client reports
 * unconfigured and the vectorize/search features stay disabled.
 */
@Service
public class VoyageEmbeddingClient implements EmbeddingClient {

	private static final String MODEL = "voyage-3.5-lite";
	private static final int MAX_INPUT_CHARS = 24_000;

	private final AppProperties props;
	private final RestClient rest;

	public VoyageEmbeddingClient(AppProperties props, RestClient.Builder builder) {
		this.props = props;
		this.rest = builder.baseUrl("https://api.voyageai.com/v1").build();
	}

	@Override
	public boolean configured() {
		return props.voyageApiKey() != null && !props.voyageApiKey().isBlank();
	}

	@Override
	public float[] embed(String text, boolean query) {
		if (!configured()) {
			throw new IllegalStateException("embeddings not configured (set CLAUDE_UI_VOYAGE_API_KEY)");
		}
		String input = text == null ? "" : text;
		if (input.length() > MAX_INPUT_CHARS) {
			input = input.substring(0, MAX_INPUT_CHARS);
		}
		JsonNode response = rest.post().uri("/embeddings")
				.header("Authorization", "Bearer " + props.voyageApiKey())
				.body(Map.of("model", MODEL, "input", List.of(input), "input_type", query ? "query" : "document"))
				.retrieve()
				.body(JsonNode.class);
		JsonNode values = response == null ? null : response.path("data").path(0).path("embedding");
		if (values == null || !values.isArray() || values.isEmpty()) {
			throw new IllegalStateException("unexpected Voyage embeddings response");
		}
		float[] embedding = new float[values.size()];
		for (int i = 0; i < values.size(); i++) {
			embedding[i] = (float) values.get(i).asDouble();
		}
		return embedding;
	}

	@Override
	public String model() {
		return MODEL;
	}
}
