package de.pamir.claude.ui.session;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Estimates USD cost for a Codex turn from its token-count usage against a
 * Settings-editable per-model price table — Codex reports no per-turn USD itself.
 * See docs/plan/phase-5.13-codex-provider.md Decision 2.
 */
final class CodexCostEstimator {

	private CodexCostEstimator() {
	}

	/**
	 * @param pricing {@code {"<model>"|"default": {"inputPer1M":n, "cachedInputPer1M":n, "outputPer1M":n}}}
	 *                (see {@link de.pamir.claude.ui.config.SettingsService#codexPricing()})
	 * @param model   the model that produced the turn
	 * @param usage   the raw token-count usage node the sidecar reported in {@code turn_complete}
	 *                (TokenUsageBreakdown-shaped: inputTokens/cachedInputTokens/outputTokens, where
	 *                inputTokens already includes cachedInputTokens as a sub-count)
	 */
	static BigDecimal estimate(JsonNode pricing, String model, JsonNode usage) {
		if (usage == null || !usage.isObject()) {
			return BigDecimal.ZERO;
		}
		JsonNode rates = pricing.path(model);
		if (!rates.isObject()) {
			rates = pricing.path("default");
		}
		if (!rates.isObject()) {
			return BigDecimal.ZERO;
		}
		long inputTokens = usage.path("inputTokens").asLong(0);
		long cachedInputTokens = usage.path("cachedInputTokens").asLong(0);
		long outputTokens = usage.path("outputTokens").asLong(0);
		long uncachedInputTokens = Math.max(0, inputTokens - cachedInputTokens);

		BigDecimal cost = rate(rates, "inputPer1M").multiply(BigDecimal.valueOf(uncachedInputTokens))
				.add(rate(rates, "cachedInputPer1M").multiply(BigDecimal.valueOf(cachedInputTokens)))
				.add(rate(rates, "outputPer1M").multiply(BigDecimal.valueOf(outputTokens)));
		return cost.divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
	}

	private static BigDecimal rate(JsonNode rates, String field) {
		return rates.hasNonNull(field) ? new BigDecimal(rates.get(field).asText()) : BigDecimal.ZERO;
	}
}
