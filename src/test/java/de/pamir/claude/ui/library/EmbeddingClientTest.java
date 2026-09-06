package de.pamir.claude.ui.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tryEmbed's log-and-continue default was reimplemented per-caller before this extraction —
 * see docs/plan/phase-9-production-hardening.md G3.
 */
class EmbeddingClientTest {

	private static EmbeddingClient of(boolean configured, java.util.function.Supplier<float[]> embed) {
		return new EmbeddingClient() {
			@Override
			public boolean configured() {
				return configured;
			}

			@Override
			public float[] embed(String text, boolean query) {
				return embed.get();
			}

			@Override
			public String model() {
				return "test-model";
			}
		};
	}

	@Test
	void tryEmbedReturnsNullWithoutCallingEmbedWhenUnconfigured() {
		EmbeddingClient client = of(false, () -> {
			throw new AssertionError("embed() must not be called when unconfigured");
		});

		assertThat(client.tryEmbed("text", false)).isNull();
	}

	@Test
	void tryEmbedReturnsTheVectorWhenConfiguredAndEmbedSucceeds() {
		float[] vector = {1f, 2f};
		EmbeddingClient client = of(true, () -> vector);

		assertThat(client.tryEmbed("text", false)).isSameAs(vector);
	}

	@Test
	void tryEmbedSwallowsAFailureAndReturnsNull() {
		EmbeddingClient client = of(true, () -> {
			throw new RuntimeException("provider down");
		});

		assertThat(client.tryEmbed("text", false)).isNull();
	}
}
