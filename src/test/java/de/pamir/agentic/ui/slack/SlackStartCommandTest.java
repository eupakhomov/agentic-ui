package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.slack.SlackStartCommand.Dev;
import de.pamir.agentic.ui.slack.SlackStartCommand.Help;
import de.pamir.agentic.ui.slack.SlackStartCommand.Invalid;
import de.pamir.agentic.ui.slack.SlackStartCommand.Review;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackStartCommandTest {

	@Test
	void reviewReadsAFullPullRequestUrlAndIgnoresTheTrailingPath() {
		Review r = (Review) SlackStartCommand.parse("review https://github.com/acme/api/pull/123");
		assertThat(r.pr().owner()).isEqualTo("acme");
		assertThat(r.pr().repo()).isEqualTo("api");
		assertThat(r.pr().number()).isEqualTo(123);
		assertThat(r.extra()).isEmpty();
		assertThat(((Review) SlackStartCommand.parse("review https://github.com/acme/api/pull/9/files#diff-1")).pr().number()).isEqualTo(9);
		assertThat(((Review) SlackStartCommand.parse("review https://github.example.com/acme/api/pull/5")).pr().number()).isEqualTo(5);
	}

	@Test
	void reviewAlsoTakesOwnerRepoHashNumberAndToleratesCodeFormattingAndCase() {
		Review r = (Review) SlackStartCommand.parse("  REVIEW  `acme/api#77`  ");
		assertThat(r.pr().owner()).isEqualTo("acme");
		assertThat(r.pr().number()).isEqualTo(77);
	}

	@Test
	void textAfterThePullRequestBecomesExtraInstructionsAndMayBeMultiLine() {
		Review r = (Review) SlackStartCommand.parse("review acme/api#1 focus on the auth changes\nand the migration");
		assertThat(r.extra()).isEqualTo("focus on the auth changes\nand the migration");
	}

	@Test
	void reviewWithoutAReadablePullRequestIsExplained() {
		assertThat(SlackStartCommand.parse("review")).isInstanceOf(Invalid.class);
		Invalid bad = (Invalid) SlackStartCommand.parse("review not-a-pr");
		assertThat(bad.reason()).contains("not-a-pr").contains("owner/repo#123");
		assertThat(SlackStartCommand.parse("review https://github.com/acme/api/issues/3")).isInstanceOf(Invalid.class);
		assertThat(SlackStartCommand.parse("review https://github.com/acme/api/pull/abc")).isInstanceOf(Invalid.class);
	}

	@Test
	void devTakesServiceBranchAndEverythingAfterAsThePromptNewlinesIncluded() {
		Dev d = (Dev) SlackStartCommand.parse("dev billing feat/rate-limit Add rate limiting\n\n- per user\n- per IP\n");
		assertThat(d.service()).isEqualTo("billing");
		assertThat(d.branch()).isEqualTo("feat/rate-limit");
		assertThat(d.prompt()).isEqualTo("Add rate limiting\n\n- per user\n- per IP");
	}

	@Test
	void devAcceptsAliasesAndSlackCodeFormatting() {
		Dev d = (Dev) SlackStartCommand.parse("Development `billing` `feat/x` do it");
		assertThat(d.service()).isEqualTo("billing");
		assertThat(d.branch()).isEqualTo("feat/x");
		assertThat(SlackStartCommand.parse("develop a b c")).isInstanceOf(Dev.class);
	}

	@Test
	void devNeedsAServiceAndABranchButThePromptMayBeLeftOutToBeAskedFor() {
		assertThat(((Invalid) SlackStartCommand.parse("dev")).reason()).contains("service").contains("branch").contains("prompt");
		assertThat(((Invalid) SlackStartCommand.parse("dev billing")).reason()).contains("service and a branch");
		Dev bare = (Dev) SlackStartCommand.parse("dev billing feat/x");
		assertThat(bare.service()).isEqualTo("billing");
		assertThat(bare.branch()).isEqualTo("feat/x");
		assertThat(bare.prompt()).isEmpty();
		assertThat(((Dev) SlackStartCommand.parse("dev billing feat/x   \n  ")).prompt()).isEmpty();
	}

	@Test
	void helpAndEmptyTextShowTheUsage() {
		assertThat(SlackStartCommand.parse("help")).isInstanceOf(Help.class);
		assertThat(SlackStartCommand.parse("?")).isInstanceOf(Help.class);
		assertThat(SlackStartCommand.parse("")).isInstanceOf(Help.class);
		assertThat(SlackStartCommand.parse(null)).isInstanceOf(Help.class);
		assertThat(SlackStartCommand.parse("   ")).isInstanceOf(Help.class);
	}

	@Test
	void anUnknownWordIsReportedByName() {
		assertThat(((Invalid) SlackStartCommand.parse("deploy now")).reason()).contains("deploy");
	}

	// ---- review-with / dev-with: the template name comes first

	@Test
	void plainCommandsCarryNoTemplate() {
		assertThat(((Review) SlackStartCommand.parse("review acme/api#1")).template()).isNull();
		assertThat(((Dev) SlackStartCommand.parse("dev api feat/x go")).template()).isNull();
	}

	@Test
	void devWithTakesTheTemplateFirstThenTheUsualArguments() {
		Dev d = (Dev) SlackStartCommand.parse("dev-with bugfix api feat/x Fix the login bug\nand add a test");
		assertThat(d.template()).isEqualTo("bugfix");
		assertThat(d.service()).isEqualTo("api");
		assertThat(d.branch()).isEqualTo("feat/x");
		assertThat(d.prompt()).isEqualTo("Fix the login bug\nand add a test");
		assertThat(((Dev) SlackStartCommand.parse("DEVELOPMENT-WITH t a b c")).template()).isEqualTo("t");
	}

	@Test
	void devWithMayLeaveThePromptOutExactlyLikeDev() {
		Dev d = (Dev) SlackStartCommand.parse("dev-with bugfix api feat/x");
		assertThat(d.template()).isEqualTo("bugfix");
		assertThat(d.prompt()).isEmpty();
	}

	@Test
	void reviewWithTakesTheTemplateFirstThenThePrAndExtraText() {
		Review r = (Review) SlackStartCommand.parse("review-with strict https://github.com/acme/api/pull/5 mind the migrations");
		assertThat(r.template()).isEqualTo("strict");
		assertThat(r.pr().number()).isEqualTo(5);
		assertThat(r.extra()).isEqualTo("mind the migrations");
	}

	@Test
	void aTemplateNameWithSpacesIsQuotedWithAnyCommonQuoteStyle() {
		assertThat(((Dev) SlackStartCommand.parse("dev-with \"Bug fix\" api feat/x go")).template()).isEqualTo("Bug fix");
		assertThat(((Dev) SlackStartCommand.parse("dev-with \u201cBug fix\u201d api feat/x go")).template()).isEqualTo("Bug fix");
		assertThat(((Dev) SlackStartCommand.parse("dev-with `Bug fix` api feat/x go")).template()).isEqualTo("Bug fix");
		assertThat(((Dev) SlackStartCommand.parse("dev-with 'Bug fix' api feat/x go")).template()).isEqualTo("Bug fix");
		Review r = (Review) SlackStartCommand.parse("review-with \"Deep review\" acme/api#9");
		assertThat(r.template()).isEqualTo("Deep review");
		assertThat(r.pr().number()).isEqualTo(9);
	}

	@Test
	void theWithCommandsExplainWhatIsMissing() {
		assertThat(((Invalid) SlackStartCommand.parse("dev-with")).reason()).contains("template name first");
		assertThat(((Invalid) SlackStartCommand.parse("review-with")).reason()).contains("template name first");
		assertThat(((Invalid) SlackStartCommand.parse("dev-with bugfix")).reason()).contains("service and a branch");
		assertThat(((Invalid) SlackStartCommand.parse("review-with strict")).reason()).contains("needs a pull request");
		assertThat(((Invalid) SlackStartCommand.parse("review-with strict nonsense")).reason()).contains("nonsense");
		assertThat(((Invalid) SlackStartCommand.parse("dev-with \"\" api feat/x go")).reason()).isNotEmpty();
	}
}
