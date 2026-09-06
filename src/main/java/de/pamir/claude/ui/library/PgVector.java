package de.pamir.claude.ui.library;

/**
 * pgvector literal formatting (e.g. {@code "[0.1,0.2,0.3]"}) — shared by every repository that
 * stores or queries an embedding column (see docs/plan/phase-9-production-hardening.md G3).
 */
public final class PgVector {

	private PgVector() {
	}

	public static String literal(float[] embedding) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < embedding.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(embedding[i]);
		}
		return sb.append(']').toString();
	}
}
