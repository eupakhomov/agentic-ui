package de.pamir.claude.ui.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Text-embedding provider abstraction (Voyage today; shared groundwork for the phase-5 RAG
 * item). Callers must check configured() first — an unconfigured client throws on embed.
 */
public interface EmbeddingClient {

	Logger EMBED_LOG = LoggerFactory.getLogger(EmbeddingClient.class);

	boolean configured();

	/** Embeds one text; query=true uses the provider's query-side encoding for search input. */
	float[] embed(String text, boolean query);

	/** Model identifier stored alongside each embedding. */
	String model();

	/**
	 * Best-effort embed: null when unconfigured, or when {@link #embed} throws (a warning is
	 * logged either way — the failure never propagates). Callers that need the failure reason
	 * surfaced (e.g. as an API warning) still call {@link #embed} directly inside their own
	 * try/catch. See docs/plan/phase-9-production-hardening.md G3.
	 */
	default float[] tryEmbed(String text, boolean query) {
		if (!configured()) {
			return null;
		}
		try {
			return embed(text, query);
		} catch (RuntimeException e) {
			EMBED_LOG.warn("embedding failed: {}", e.getMessage());
			return null;
		}
	}
}
