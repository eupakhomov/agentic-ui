package de.pamir.claude.ui.library;

/**
 * Text-embedding provider abstraction (Voyage today; shared groundwork for the phase-5 RAG
 * item). Callers must check configured() first — an unconfigured client throws on embed.
 */
public interface EmbeddingClient {

	boolean configured();

	/** Embeds one text; query=true uses the provider's query-side encoding for search input. */
	float[] embed(String text, boolean query);

	/** Model identifier stored alongside each embedding. */
	String model();
}
