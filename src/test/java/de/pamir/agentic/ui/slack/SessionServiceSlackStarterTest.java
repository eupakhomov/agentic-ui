package de.pamir.agentic.ui.slack;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** The settings a Slack-started session is created with, with and without a template. */
class SessionServiceSlackStarterTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static ObjectNode json(String s) {
		return (ObjectNode) MAPPER.readTree(s);
	}

	private static final String LAST = "{\"model\":\"opus\",\"permissionMode\":\"bypassPermissions\",\"allowedTools\":[\"Bash\"],"
			+ "\"instructions\":\"be terse\",\"skillSources\":[{\"type\":\"dir\",\"ref\":\"/skills/a\"}],"
			+ "\"agentSources\":[{\"type\":\"file\",\"ref\":\"/agents/b.md\"}],\"servicePath\":\"/eco/other\"}";

	@Test
	void withoutATemplateTheLastSessionsSettingsAreInheritedAsTheDashboardQuickStartDoes() {
		ObjectNode o = SessionServiceSlackStarter.buildOverrides(json(LAST), null, MAPPER);
		assertThat(o.path("model").asText()).isEqualTo("opus");
		assertThat(o.path("permissionMode").asText()).isEqualTo("bypassPermissions"); // inherited exactly; documented
		assertThat(o.path("allowedTools")).hasSize(1);
		assertThat(o.path("skillSources")).hasSize(1);
		assertThat(o.path("instructions").asText()).isEqualTo("be terse");
	}

	@Test
	void aTemplateWinsOverEveryKeyItSetsAndItsOwnAssetsReplaceTheInheritedOnes() {
		ObjectNode template = json("{\"model\":\"haiku\",\"permissionMode\":\"default\",\"kickoffPrompt\":\"SHOULD NOT BE SENT\","
				+ "\"baseBranch\":\"develop\"}");
		ObjectNode o = SessionServiceSlackStarter.buildOverrides(json(LAST), template, MAPPER);

		// what the template sets is removed from the overrides so the template's value is the one that applies
		assertThat(o.has("model")).isFalse();
		assertThat(o.has("permissionMode")).isFalse();
		assertThat(o.has("baseBranch")).isFalse();
		// the inherited asset lists go too, so the template's linked skills/agents are merged server-side instead
		assertThat(o.has("skillSources")).isFalse();
		assertThat(o.has("agentSources")).isFalse();
		// what the template does not set is still inherited
		assertThat(o.path("allowedTools")).hasSize(1);
		assertThat(o.path("instructions").asText()).isEqualTo("be terse");
	}

	@Test
	void slackMirroringIsAlwaysOnAndNoKickoffPromptEverFiresWhateverTheTemplateSays() {
		ObjectNode withTemplate = SessionServiceSlackStarter.buildOverrides(json(LAST),
				json("{\"slackEnabled\":false,\"kickoffPrompt\":\"x\"}"), MAPPER);
		assertThat(withTemplate.path("slackEnabled").asBoolean()).isTrue();
		assertThat(withTemplate.path("kickoffPrompt").asText()).isEmpty();

		ObjectNode plain = SessionServiceSlackStarter.buildOverrides(json("{}"), null, MAPPER);
		assertThat(plain.path("slackEnabled").asBoolean()).isTrue();
		assertThat(plain.path("kickoffPrompt").asText()).isEmpty();
	}

	@Test
	void aMissingLastSessionIsFine() {
		ObjectNode o = SessionServiceSlackStarter.buildOverrides(null, null, MAPPER);
		assertThat(o.path("slackEnabled").asBoolean()).isTrue();
		assertThat(MAPPER.createObjectNode().has("model")).isFalse();
	}

	@Test
	void theInheritedSettingsAreNotMutatedByBuildingTheOverrides() {
		ObjectNode last = json(LAST);
		SessionServiceSlackStarter.buildOverrides(last, json("{\"model\":\"haiku\"}"), MAPPER);
		assertThat(last.path("model").asText()).isEqualTo("opus");
		assertThat(last.has("slackEnabled")).isFalse();
	}
}
