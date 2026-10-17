package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.slack.SlackBlocks.ParentView;
import de.pamir.agentic.ui.slack.SlackBlocks.PlanMessage;
import de.pamir.agentic.ui.slack.SlackMessaging.SlackMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlackBlocksTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode actionsOf(SlackMessage m) {
		for (JsonNode block : m.blocks()) {
			if ("actions".equals(block.path("type").asText())) {
				return block;
			}
		}
		return null;
	}

	private static List<String> actionIds(SlackMessage m) {
		List<String> ids = new ArrayList<>();
		JsonNode actions = actionsOf(m);
		if (actions != null) {
			actions.path("elements").forEach(e -> ids.add(e.path("action_id").asText()));
		}
		return ids;
	}

	private static ParentView parent(boolean controls, String openUrl) {
		return new ParentView("s-1", "Fix <login> & co", ":large_blue_circle: working", "claude · feat/x · development",
				null, "Do the thing", openUrl, controls);
	}

	// ---- parent

	@Test
	void parentShowsControlsAndEscapesSlackControlCharacters() {
		SlackMessage m = SlackBlocks.parent(parent(true, "https://dash.example"));
		assertThat(actionIds(m)).containsExactly("session_stop", "session_status", "session_close", "session_open");
		assertThat(actionsOf(m).path("block_id").asText()).isEqualTo("session:s-1");
		assertThat(m.blocks().get(0).path("text").path("text").asText()).contains("Fix &lt;login&gt; &amp; co");
		JsonNode stop = actionsOf(m).path("elements").get(0);
		assertThat(stop.path("confirm").isObject()).isTrue();
		JsonNode close = actionsOf(m).path("elements").get(2);
		assertThat(close.path("confirm").path("title").path("text").asText()).isEqualTo("Close this session?");
		assertThat(close.path("confirm").path("text").path("text").asText()).contains("cannot be reopened").contains("uncommitted");
		assertThat(actionsOf(m).path("elements").get(3).path("url").asText()).isEqualTo("https://dash.example");
	}

	@Test
	void parentOmitsOpenWithoutAPublicUrlAndAllButtonsWhenClosed() {
		assertThat(actionIds(SlackBlocks.parent(parent(true, null)))).containsExactly("session_stop", "session_status", "session_close");
		assertThat(actionsOf(SlackBlocks.parent(parent(false, "https://dash.example")))).isNull();
	}

	// ---- permission

	@Test
	void permissionCarriesTheRequestInItsBlockIdAndButtonValues() {
		SlackMessage m = SlackBlocks.permission("s-1", "p7", "Bash", "rm -rf build", "My session");
		assertThat(actionIds(m)).containsExactly("perm_allow", "perm_deny");
		assertThat(actionsOf(m).path("block_id").asText()).isEqualTo("perm:s-1:p7");
		assertThat(actionsOf(m).path("elements").get(0).path("value").asText()).isEqualTo("p7");
		assertThat(m.blocks().get(0).path("text").path("text").asText()).contains("`Bash`").contains("rm -rf build");
		assertThat(m.text()).contains("My session").contains("Bash");
	}

	@Test
	void aCommandCannotBreakOutOfItsCodeFence() {
		SlackMessage m = SlackBlocks.permission("s", "p", "Bash", "echo ``` <@U1> &", "n");
		String text = m.blocks().get(0).path("text").path("text").asText();
		assertThat(text).doesNotContain("``` <@U1>").contains("&lt;@U1&gt;").contains("&amp;");
	}

	// ---- plan

	@Test
	void aShortPlanIsInlineWithApprovalButtons() {
		PlanMessage pm = SlackBlocks.plan("s-1", "p2", "## Steps\n\n**One** do a\n\nTwo [docs](https://x.y)", "n");
		assertThat(pm.needsSnippet()).isFalse();
		assertThat(actionIds(pm.message())).containsExactly("plan_approve", "plan_keep");
		assertThat(actionsOf(pm.message()).path("block_id").asText()).isEqualTo("plan:s-1:p2");
		String all = pm.message().blocks().toString();
		assertThat(all).contains("*Steps*").contains("*One* do a").contains("<https://x.y|docs>");
	}

	@Test
	void aLongPlanKeepsOnlyItsFirstChunkInlineAndAsksForASnippet() {
		String plan = ("paragraph ".repeat(40) + "\n\n").repeat(60); // ~24k chars
		PlanMessage pm = SlackBlocks.plan("s", "p", plan, "n");
		assertThat(pm.needsSnippet()).isTrue();
		for (JsonNode block : pm.message().blocks()) {
			assertThat(block.path("text").path("text").asText().length()).isLessThanOrEqualTo(SlackBlocks.SECTION_MAX);
		}
		assertThat(pm.message().blocks().toString()).contains("follows as a file");
	}

	@Test
	void chunkingNeverExceedsTheSectionLimitEvenForOneHugeParagraph() {
		List<String> chunks = SlackBlocks.chunk("x".repeat(10_000), SlackBlocks.SECTION_MAX);
		assertThat(chunks).hasSize(4).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(SlackBlocks.SECTION_MAX));
		assertThat(String.join("", chunks)).hasSize(10_000);
	}

	// ---- question

	private static JsonNode question(String json) {
		return MAPPER.readTree(json);
	}

	@Test
	void aSingleChoiceQuestionGetsOneButtonPerOption() {
		JsonNode input = question("{\"questions\":[{\"question\":\"Which DB?\",\"header\":\"DB\",\"multiSelect\":false,"
				+ "\"options\":[{\"label\":\"Postgres\",\"description\":\"relational\"},{\"label\":\"SQLite\",\"description\":\"\"}]}]}");
		SlackMessage m = SlackBlocks.question("s-1", "q3", input, "n");
		assertThat(actionIds(m)).containsExactly("ask_option_1", "ask_option_2");
		assertThat(actionsOf(m).path("block_id").asText()).isEqualTo("ask:s-1:q3");
		assertThat(actionsOf(m).path("elements").get(1).path("value").asText()).isEqualTo("SQLite");
		assertThat(m.blocks().toString()).contains("Which DB?").contains("relational");
	}

	@Test
	void moreThanFiveOptionsBecomeASelect() {
		StringBuilder opts = new StringBuilder();
		for (int i = 1; i <= 7; i++) {
			opts.append(i > 1 ? "," : "").append("{\"label\":\"opt").append(i).append("\",\"description\":\"\"}");
		}
		SlackMessage m = SlackBlocks.question("s", "q",
				question("{\"questions\":[{\"question\":\"Pick\",\"options\":[" + opts + "]}]}"), "n");
		JsonNode element = actionsOf(m).path("elements").get(0);
		assertThat(element.path("type").asText()).isEqualTo("static_select");
		assertThat(element.path("options")).hasSize(7);
	}

	@Test
	void multiSelectOrMultipleQuestionsFallBackToTextWithoutButtons() {
		SlackMessage multi = SlackBlocks.question("s", "q", question(
				"{\"questions\":[{\"question\":\"A\",\"multiSelect\":true,\"options\":[{\"label\":\"x\"}]}]}"), "n");
		assertThat(actionsOf(multi)).isNull();
		assertThat(multi.blocks().toString()).contains("dashboard");
		SlackMessage two = SlackBlocks.question("s", "q", question(
				"{\"questions\":[{\"question\":\"A\",\"options\":[{\"label\":\"x\"}]},{\"question\":\"B\",\"options\":[{\"label\":\"y\"}]}]}"), "n");
		assertThat(actionsOf(two)).isNull();
	}

	@Test
	void aMalformedQuestionStillProducesAMessage() {
		SlackMessage m = SlackBlocks.question("s", "q", question("{}"), "n");
		assertThat(m.blocks()).isNotEmpty();
		assertThat(actionsOf(m)).isNull();
	}

	// ---- Slack's structural rules, learned from invalid_blocks in the live replay

	@Test
	void actionIdsAreUniqueWithinEveryMessage() {
		JsonNode manyOptions = question("{\"questions\":[{\"question\":\"Pick\",\"options\":[{\"label\":\"a\"},{\"label\":\"b\"},{\"label\":\"c\"}]}]}");
		List<SlackMessage> all = List.of(
				SlackBlocks.parent(parent(true, "https://dash.example")),
				SlackBlocks.permission("s", "p", "Bash", "ls", "n"),
				SlackBlocks.plan("s", "p", "a plan", "n").message(),
				SlackBlocks.question("s", "q", manyOptions, "n"));
		for (SlackMessage m : all) {
			List<String> ids = actionIds(m);
			assertThat(ids).doesNotHaveDuplicates();
		}
	}

	// ---- misc

	@Test
	void settledMessagesHaveNoButtons() {
		SlackMessage m = SlackBlocks.settled("*Permission* — `Bash`", "Allowed on the dashboard");
		assertThat(actionsOf(m)).isNull();
		assertThat(m.blocks().toString()).contains("Allowed on the dashboard");
	}

	@Test
	void withPrefixTagsTheFirstSectionOnly() {
		SlackMessage m = SlackBlocks.withPrefix(SlackBlocks.notice("f", "hello"), "*child* · ");
		assertThat(m.blocks().get(0).path("text").path("text").asText()).isEqualTo("*child* · hello");
	}

	@Test
	void markdownBecomesMrkdwn() {
		assertThat(SlackBlocks.toMrkdwn("# Title\n**bold** & <tag> [a](https://b.c)"))
				.isEqualTo("*Title*\n*bold* &amp; &lt;tag&gt; <https://b.c|a>");
	}
}
