package de.pamir.claude.ui.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Was copy-pasted verbatim across four repositories before extraction — see
 * docs/plan/phase-9-production-hardening.md G3.
 */
class PgVectorTest {

	@Test
	void formatsAnEmbeddingAsACommaSeparatedBracketedLiteral() {
		assertThat(PgVector.literal(new float[] {0.1f, 0.25f, -1f})).isEqualTo("[0.1,0.25,-1.0]");
	}

	@Test
	void formatsAnEmptyEmbeddingAsEmptyBrackets() {
		assertThat(PgVector.literal(new float[0])).isEqualTo("[]");
	}

	@Test
	void formatsASingleValueWithoutATrailingComma() {
		assertThat(PgVector.literal(new float[] {3.5f})).isEqualTo("[3.5]");
	}
}
