package de.pamir.claude.ui.session;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure math, no Spring/DB — see docs/plan/phase-9-production-hardening.md T1. */
class CodexCostEstimatorTest {

	private final JsonMapper mapper = new JsonMapper();

	@Test
	void pricesUncachedAndCachedInputSeparatelyFromOutput() {
		ObjectNode pricing = mapper.createObjectNode();
		ObjectNode rates = pricing.putObject("gpt-5-codex");
		rates.put("inputPer1M", "3.00");
		rates.put("cachedInputPer1M", "0.30");
		rates.put("outputPer1M", "15.00");

		ObjectNode usage = mapper.createObjectNode();
		usage.put("inputTokens", 1_000_000L); // includes the cached sub-count below
		usage.put("cachedInputTokens", 400_000L);
		usage.put("outputTokens", 200_000L);

		BigDecimal cost = CodexCostEstimator.estimate(pricing, "gpt-5-codex", usage);

		// uncached 600k * 3.00/1M = 1.80; cached 400k * 0.30/1M = 0.12; output 200k * 15.00/1M = 3.00
		assertThat(cost).isEqualByComparingTo("4.92");
	}

	@Test
	void fallsBackToDefaultRatesForAnUnknownModel() {
		ObjectNode pricing = mapper.createObjectNode();
		ObjectNode rates = pricing.putObject("default");
		rates.put("inputPer1M", "2");
		rates.put("cachedInputPer1M", "0.5");
		rates.put("outputPer1M", "8");

		ObjectNode usage = mapper.createObjectNode();
		usage.put("inputTokens", 500_000L);
		usage.put("cachedInputTokens", 0L);
		usage.put("outputTokens", 100_000L);

		BigDecimal cost = CodexCostEstimator.estimate(pricing, "some-future-model", usage);

		// 500k * 2/1M = 1.00; 100k * 8/1M = 0.80
		assertThat(cost).isEqualByComparingTo("1.80");
	}

	@Test
	void returnsZeroWhenNeitherModelNorDefaultRatesExist() {
		ObjectNode pricing = mapper.createObjectNode(); // no "default" entry
		ObjectNode usage = mapper.createObjectNode();
		usage.put("inputTokens", 1_000L);
		usage.put("outputTokens", 1_000L);

		assertThat(CodexCostEstimator.estimate(pricing, "unknown", usage)).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void returnsZeroWhenUsageIsMissingOrNotAnObject() {
		ObjectNode pricing = mapper.createObjectNode();
		pricing.putObject("default").put("inputPer1M", "10");

		assertThat(CodexCostEstimator.estimate(pricing, "default", null)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(CodexCostEstimator.estimate(pricing, "default", mapper.nullNode())).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void treatsMissingRateFieldsAsZero() {
		ObjectNode pricing = mapper.createObjectNode();
		pricing.putObject("default").put("outputPer1M", "10"); // no input rates at all

		ObjectNode usage = mapper.createObjectNode();
		usage.put("inputTokens", 1_000_000L);
		usage.put("cachedInputTokens", 1_000_000L);
		usage.put("outputTokens", 500_000L);

		// only output is priced: 500k * 10/1M = 5.00
		assertThat(CodexCostEstimator.estimate(pricing, "default", usage)).isEqualByComparingTo("5.00");
	}
}
