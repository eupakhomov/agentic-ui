package de.pamir.claude.ui.session;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SystemSessionService.runSystemTurn is stubbed via a tiny subclass (same pattern as
 * SessionConfigFactoryTest's fakeSettings) rather than a mock — see
 * docs/plan/phase-9-production-hardening.md G1/T1.
 */
class SystemTurnClientTest {

	private static SystemSessionService fakeSystemSessionService(String reply) {
		return new SystemSessionService(null, null, null, null, null, new JsonMapper(), null) {
			@Override
			public String runSystemTurn(String prompt, String modelOverride, SystemTurnLane lane, Duration timeout) {
				return reply;
			}
		};
	}

	private SystemTurnClient clientReturning(String reply) {
		return new SystemTurnClient(fakeSystemSessionService(reply), new JsonMapper());
	}

	@Test
	void textStripsSurroundingWhitespaceButNotFences() {
		SystemTurnClient client = clientReturning("  ```\nhello\n```  ");

		assertThat(client.text("prompt", SystemTurnLane.INTERACTIVE, Duration.ofSeconds(1))).isEqualTo("```\nhello\n```");
	}

	@Test
	void jsonStripsAWrappingCodeFenceBeforeParsing() {
		SystemTurnClient client = clientReturning("```json\n{\"a\":1}\n```");

		JsonNode node = client.json("prompt", SystemTurnLane.INTERACTIVE, Duration.ofSeconds(1));

		assertThat(node.path("a").asInt()).isEqualTo(1);
	}

	@Test
	void jsonParsesAPlainUnfencedObject() {
		SystemTurnClient client = clientReturning("{\"a\":1}");

		assertThat(client.json("prompt", SystemTurnLane.INTERACTIVE, Duration.ofSeconds(1)).path("a").asInt()).isEqualTo(1);
	}

	@Test
	void jsonThrowsWithATruncatedPreviewOnUnparsableInput() {
		SystemTurnClient client = clientReturning("not json at all");

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> client.json("prompt", SystemTurnLane.INTERACTIVE, Duration.ofSeconds(1)));
		assertThat(e.getMessage()).contains("not json at all");
	}

	@Test
	void jsonWithModelOverrideDelegatesToTheFourArgRunSystemTurn() {
		SystemTurnClient client = clientReturning("{\"ok\":true}");

		assertThat(client.json("prompt", "haiku", SystemTurnLane.INTERACTIVE, Duration.ofSeconds(1)).path("ok").asBoolean()).isTrue();
	}

	@Test
	void stripFencesRemovesAJsonFenceLabelAndSurroundingWhitespace() {
		assertThat(SystemTurnClient.stripFences("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
	}

	@Test
	void stripFencesIsANoOpWithoutFences() {
		assertThat(SystemTurnClient.stripFences("plain text")).isEqualTo("plain text");
	}

	@Test
	void stripFencesHandlesNull() {
		assertThat(SystemTurnClient.stripFences(null)).isEmpty();
	}

	@Test
	void truncateLeavesShortStringsUntouched() {
		assertThat(SystemTurnClient.truncate("short", 300)).isEqualTo("short");
	}

	@Test
	void truncateCapsLongStringsWithAnEllipsis() {
		String result = SystemTurnClient.truncate("a".repeat(400), 300);

		assertThat(result).hasSize(301).endsWith("…");
	}

	@Test
	void truncateHandlesNull() {
		assertThat(SystemTurnClient.truncate(null, 300)).isEmpty();
	}

	@Test
	void lowercaseTagsStripsLowercasesAndSkipsBlanks() {
		JsonNode node = new JsonMapper().readTree("{\"tags\":[\" Foo \", \"BAR\", \"\", \"  \"]}");

		assertThat(SystemTurnClient.lowercaseTags(node)).isEqualTo(List.of("foo", "bar"));
	}

	@Test
	void lowercaseTagsIsEmptyWhenTagsIsMissing() {
		JsonNode node = new JsonMapper().readTree("{}");

		assertThat(SystemTurnClient.lowercaseTags(node)).isEmpty();
	}
}
