package de.pamir.agentic.ui.slack;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SlackRedactorTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void knownTokenShapesAreMaskedWholesale() {
		String masked = SlackRedactor.mask("a xoxb-12345678901-abcdefghij b xapp-1-A0FAKEAPP01-123456-abcdef c "
				+ "ghp_abcdefghijklmnopqrstuvwxyz0123 d sk-ant-api03-abcdefghijklmnop e AKIAABCDEFGHIJKLMNOP f "
				+ "lin_api_abcdefghijklmnopqrstuv");
		assertThat(masked).doesNotContain("12345678901", "A0FAKEAPP01", "abcdefghijklmnopqrstuvwxyz0123",
				"api03-abcdefghijklmnop", "ABCDEFGHIJKLMNOP", "abcdefghijklmnopqrstuv");
		assertThat(masked).contains("xox*-***", "xapp-***", "gh*_***", "sk-***", "AKIA***", "lin_api_***");
	}

	@Test
	void authorizationHeadersAndBearerTokensAreMasked() {
		assertThat(SlackRedactor.mask("curl -H \"Authorization: Bearer abc.def-ghi123\" https://x"))
				.doesNotContain("abc.def-ghi123").contains("Authorization: Bearer ***");
		assertThat(SlackRedactor.mask("curl -H 'authorization: token s3cr3tvalue' https://x"))
				.doesNotContain("s3cr3tvalue");
		assertThat(SlackRedactor.mask("header is Bearer aaaaaaaaaaaaaaaa here")).doesNotContain("aaaaaaaaaaaaaaaa");
	}

	@Test
	void credentialsInUrlsAreMasked() {
		assertThat(SlackRedactor.mask("git clone https://user:hunter2@github.com/o/r.git"))
				.doesNotContain("hunter2").contains("https://***:***@github.com");
	}

	@Test
	void assignmentsKeepTheNameAndLoseTheValue() {
		assertThat(SlackRedactor.mask("export DB_PASSWORD=hunter2 && run")).isEqualTo("export DB_PASSWORD=*** && run");
		assertThat(SlackRedactor.mask("API_KEY: abc123xyz")).isEqualTo("API_KEY: ***");
		assertThat(SlackRedactor.mask("{\"password\": \"hunter2\", \"user\": \"bob\"}"))
				.doesNotContain("hunter2").contains("bob");
		assertThat(SlackRedactor.mask("mytool --password hunter2 --verbose")).doesNotContain("hunter2").contains("--verbose");
		assertThat(SlackRedactor.mask("curl --token=abcd1234 x")).doesNotContain("abcd1234");
	}

	@Test
	void ordinaryTextAndLookalikeNamesAreLeftAlone() {
		String text = "max_tokens: 4096, tokenizer=fast, keyboard=us, see README.md and run npm test";
		assertThat(SlackRedactor.mask(text)).isEqualTo(text);
	}

	@Test
	void jwtsAndPrivateKeysAreMasked() {
		assertThat(SlackRedactor.mask("t=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop"))
				.contains("[jwt]");
		assertThat(SlackRedactor.mask("-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----"))
				.isEqualTo("[private key]");
	}

	@Test
	void nullAndEmptyPassThrough() {
		assertThat(SlackRedactor.mask(null)).isNull();
		assertThat(SlackRedactor.mask("")).isEmpty();
	}

	// ---- SlackToolSummary

	private static JsonNode json(String s) {
		return MAPPER.readTree(s);
	}

	@Test
	void aBashCommandIsShownButMasked() {
		String shown = SlackToolSummary.summarize("Bash", json("{\"command\": \"export TOKEN=abc123 && deploy\"}"), false);
		assertThat(shown).isEqualTo("export TOKEN=*** && deploy");
	}

	@Test
	void verboseModeShowsTheRawCommand() {
		assertThat(SlackToolSummary.summarize("Bash", json("{\"command\": \"export TOKEN=abc123\"}"), true))
				.isEqualTo("export TOKEN=abc123");
	}

	@Test
	void aFileEditShowsThePathAndNeverTheContent() {
		String shown = SlackToolSummary.summarize("Edit",
				json("{\"file_path\": \"/repo/App.java\", \"old_string\": \"secret-old\", \"new_string\": \"secret-new\"}"), false);
		assertThat(shown).isEqualTo("/repo/App.java").doesNotContain("secret");
		assertThat(SlackToolSummary.summarize("Write",
				json("{\"file_path\": \"/repo/.env\", \"content\": \"TOKEN=abc\"}"), false)).isEqualTo("/repo/.env");
	}

	@Test
	void anUnknownToolShowsShortScalarsAndSkipsContentAndEnv() {
		String shown = SlackToolSummary.summarize("mcp__x__do",
				json("{\"id\": 7, \"body\": \"long private text\", \"env\": {\"A\": \"b\"}, \"name\": \"report\", "
						+ "\"nested\": {\"a\": 1}}"), false);
		assertThat(shown).isEqualTo("id=7, name=report");
	}

	@Test
	void longSummariesAreTruncated() {
		String shown = SlackToolSummary.summarize("Bash", json("{\"command\": \"" + "x".repeat(2000) + "\"}"), false);
		assertThat(shown).hasSize(SlackToolSummary.MAX_CHARS + 1).endsWith("…");
	}
}
