package de.pamir.agentic.ui.slack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static de.pamir.agentic.ui.slack.FakeStarter.pr;
import static org.assertj.core.api.Assertions.assertThat;

/** The decisions behind {@code @bot review} / {@code @bot dev}, against a fake starter (no git, gh or database). */
class SlackCommandHandlerTest {

	private final FakeStarter starter = new FakeStarter();
	private final List<String> replies = new ArrayList<>();
	private final SlackCommandHandler handler = new SlackCommandHandler(starter, Runnable::run);

	@BeforeEach
	void setUp() {
		starter.polyrepo("api", "acme/api").polyrepo("web", "acme/web").polyrepo("billing-service", "acme/billing");
		starter.prs.put(12, pr(12, "OPEN", false, "src/Main.java"));
	}

	private String run(String text) {
		replies.clear();
		handler.handle("U1", text, replies::add);
		assertThat(replies).hasSize(1);
		return replies.get(0);
	}

	private static final String PR12 = "review https://github.com/acme/api/pull/12";

	// ---------------------------------------------------------------- review

	@Test
	void reviewStartsAReviewSessionOnTheServiceWhoseCloneMatchesThePr() {
		String reply = run(PR12);

		assertThat(starter.started).hasSize(1);
		SlackSessionStarter.StartRequest r = starter.started.get(0);
		assertThat(r.sessionType()).isEqualTo("review");
		assertThat(r.name()).isEqualTo("review: feat/fix-12");
		assertThat(r.branch()).isEqualTo("feat/fix-12");
		assertThat(r.baseBranch()).isEqualTo("main");
		assertThat(r.repoPath()).isEqualTo("/eco/api");
		assertThat(r.servicePath()).isEqualTo("/eco/api");
		assertThat(r.prUrl()).isEqualTo("https://github.com/acme/api/pull/12");
		assertThat(r.slackUser()).isEqualTo("U1");
		assertThat(r.prompt()).contains("#12").contains("Fix the thing | now").contains("by alice").contains("`feat/fix-12`")
				.contains("submit_pr_review");
		assertThat(starter.prLookups).containsExactly("/eco/api");
		assertThat(reply).startsWith(":mag:").contains("<https://github.com/acme/api/pull/12|#12 Fix the thing / now>")
				.contains("`api`").contains("model haiku").contains("permissions: default");
	}

	@Test
	void theRepositoryMatchIgnoresCaseAndExtraTextIsAddedToTheInstruction() {
		starter.origins.put("/eco/api", "ACME/Api");
		run(PR12 + " please focus on the auth changes");
		assertThat(starter.started).hasSize(1);
		assertThat(starter.started.get(0).prompt()).endsWith("Additional instructions from the requester: please focus on the auth changes");
	}

	@Test
	void aPrFromARepositoryWeHaveNoCloneOfIsReportedWithTheKnownServices() {
		String reply = run("review https://github.com/other/thing/pull/3");
		assertThat(reply).startsWith(":x:").contains("other/thing").contains("`api`").contains("`billing-service`").contains("`web`");
		assertThat(starter.started).isEmpty();
		assertThat(starter.prLookups).isEmpty(); // nothing asked of GitHub for a repo we cannot use
	}

	@Test
	void aRepositoryClonedTwiceIsAmbiguous() {
		starter.polyrepo("api-copy", "acme/api");
		String reply = run(PR12);
		assertThat(reply).contains("cloned more than once").contains("/eco/api").contains("/eco/api-copy");
		assertThat(starter.started).isEmpty();
	}

	@Test
	void aClosedMergedOrForkedPullRequestIsRefused() {
		starter.prs.put(20, pr(20, "MERGED", false));
		starter.prs.put(21, pr(21, "CLOSED", false));
		starter.prs.put(22, pr(22, "OPEN", true));
		assertThat(run("review acme/api#20")).contains("merged").contains("only open");
		assertThat(run("review acme/api#21")).contains("closed");
		assertThat(run("review acme/api#22")).contains("fork");
		assertThat(starter.started).isEmpty();
	}

	@Test
	void aGithubFailureIsReportedNotThrown() {
		starter.failView = new IllegalStateException("gh pr view failed: Could not resolve to a PullRequest\nwith more detail");
		String reply = run(PR12);
		assertThat(reply).startsWith(":x:").contains("Could not read").contains("Could not resolve to a PullRequest").doesNotContain("more detail");
		assertThat(starter.started).isEmpty();
	}

	@Test
	void anUnknownPullRequestNumberIsReported() {
		assertThat(run("review acme/api#999")).contains(":x:").contains("no pull request found");
	}

	// ---- monorepo

	private void monorepo() {
		starter.services.clear();
		starter.origins.clear();
		starter.monorepoPackage("mono", "api", "acme/mono").monorepoPackage("mono", "web", "acme/mono");
		starter.prs.put(30, pr(30, "OPEN", false, "packages/api/src/A.java", "packages/api/pom.xml"));
		starter.prs.put(31, pr(31, "OPEN", false, "packages/api/A.java", "packages/web/B.ts"));
		starter.prs.put(32, pr(32, "OPEN", false, "README.md", ".github/ci.yml"));
		starter.prs.put(33, pr(33, "OPEN", false, "packages/webby/x.ts"));
	}

	@Test
	void inAMonorepoTheOnePackageTheChangedFilesLiveInIsChosen() {
		monorepo();
		String reply = run("review acme/mono#30");
		SlackSessionStarter.StartRequest r = starter.started.get(0);
		assertThat(r.repoPath()).isEqualTo("/eco/mono");
		assertThat(r.servicePath()).isEqualTo("/eco/mono/packages/api");
		assertThat(reply).contains("`api`");
	}

	@Test
	void changesSpanningSeveralPackagesOrNoneAreNotGuessed() {
		monorepo();
		assertThat(run("review acme/mono#31")).contains("several packages").contains("`api`").contains("`web`");
		assertThat(run("review acme/mono#32")).contains("no files inside any package").contains("`api`");
		assertThat(starter.started).isEmpty();
	}

	@Test
	void aPackageFolderIsMatchedWholeNotByPrefix() {
		monorepo();
		// packages/webby is not packages/web
		assertThat(run("review acme/mono#33")).contains("no files inside any package");
	}

	@Test
	void aRepoRootServiceCatchesFilesOutsideEveryPackageButTheDeepestFolderWins() {
		monorepo();
		starter.services.add(new de.pamir.agentic.ui.git.ServiceCatalog.Service("mono", "/eco/mono", "/eco/mono", false));
		run("review acme/mono#32"); // root-level files belong to the root service
		assertThat(starter.started.get(0).servicePath()).isEqualTo("/eco/mono");
		run("review acme/mono#30"); // package files still go to the package
		assertThat(starter.started.get(1).servicePath()).isEqualTo("/eco/mono/packages/api");
	}

	// ---------------------------------------------------------------- dev

	@Test
	void devStartsADevelopmentSessionWithTheWholePromptAndTheDefaultBaseBranch() {
		starter.defaultBase = "develop";
		String reply = run("dev api feat/login Add rate limiting\n- per user\n- per IP");

		SlackSessionStarter.StartRequest r = starter.started.get(0);
		assertThat(r.sessionType()).isEqualTo("development");
		assertThat(r.name()).isEqualTo("feat/login");
		assertThat(r.branch()).isEqualTo("feat/login");
		assertThat(r.baseBranch()).isEqualTo("develop");
		assertThat(r.repoPath()).isEqualTo("/eco/api");
		assertThat(r.servicePath()).isEqualTo("/eco/api");
		assertThat(r.prUrl()).isNull();
		assertThat(r.prompt()).isEqualTo("Add rate limiting\n- per user\n- per IP");
		assertThat(r.slackUser()).isEqualTo("U1");
		assertThat(reply).startsWith(":rocket:").contains("`api`").contains("`feat/login`").contains("`develop`")
				.contains("permissions: default");
	}

	@Test
	void theServiceNameMatchesExactlyIgnoringCaseOtherwiseByUniquePrefix() {
		run("dev API feat/a do it");
		run("dev bill feat/b do it");
		assertThat(starter.started).extracting(r -> r.servicePath()).containsExactly("/eco/api", "/eco/billing-service");
	}

	@Test
	void anUnknownOrAmbiguousServiceListsWhatIsAvailable() {
		assertThat(run("dev nope feat/a do it")).startsWith(":x:").contains("No service called `nope`").contains("`api`").contains("`web`");
		starter.polyrepo("web-admin", "acme/webadmin");
		assertThat(run("dev we feat/a do it")).contains("matches several services").contains("`web`").contains("`web-admin`");
		starter.polyrepo("web", "acme/web2"); // two services with the very same name
		assertThat(run("dev web feat/a do it")).contains("matches several services").contains("/eco/web");
		assertThat(starter.started).isEmpty();
	}

	@Test
	void unusableBranchNamesAreRefusedBeforeAnythingIsStarted() {
		for (String bad : List.of("-rf", "a..b", "feat/", "x;rm", "$(id)", "a//b", "/abs", ".hidden", "name.lock", "end.")) {
			assertThat(run("dev api " + bad + " do it")).as(bad).startsWith(":x:").contains("not a usable branch name");
		}
		assertThat(starter.started).isEmpty();
		assertThat(SlackCommandHandler.branchProblem("feat/ENG-12_ok.v2")).isNull();
	}

	@Test
	void aSessionThatCannotBeStartedIsExplainedInOneLine() {
		starter.failStart = new IllegalStateException("max concurrent sessions reached (4)\nat some.stack.Trace");
		String reply = run("dev api feat/a do it");
		assertThat(reply).startsWith(":x:").contains("Could not start").contains("max concurrent sessions reached (4)").doesNotContain("Trace");
	}

	// ---------------------------------------------------------------- dev without a prompt: ask for it

	private final List<SlackCommandHandler.AwaitedPrompt> awaiting = new ArrayList<>();

	private String runAwaiting(String text) {
		replies.clear();
		awaiting.clear();
		handler.handle("U1", text, replies::add, awaiting::add);
		assertThat(replies).hasSize(1);
		return replies.get(0);
	}

	@Test
	void aDevRequestWithoutAPromptAsksForItMentioningTheRequesterAndStartsNothing() {
		String reply = runAwaiting("dev api feat/x");
		assertThat(reply).startsWith(":speech_balloon:").contains("<@U1>").contains("`api`").contains("`feat/x`")
				.contains("Reply in this thread").contains("cancel");
		assertThat(awaiting).hasSize(1);
		assertThat(awaiting.get(0).user()).isEqualTo("U1");
		assertThat(awaiting.get(0).service().path()).isEqualTo("/eco/api");
		assertThat(awaiting.get(0).branch()).isEqualTo("feat/x");
		assertThat(starter.started).isEmpty();
	}

	@Test
	void aTypoInTheServiceOrBranchIsCaughtBeforeAskingForAPrompt() {
		assertThat(runAwaiting("dev nope feat/x")).startsWith(":x:").contains("No service called");
		assertThat(awaiting).isEmpty();
		assertThat(runAwaiting("dev api -rf")).startsWith(":x:").contains("not a usable branch name");
		assertThat(awaiting).isEmpty();
	}

	@Test
	void aCallerThatCannotWaitStillGetsTheOldErrorForAMissingPrompt() {
		assertThat(run("dev api feat/x")).startsWith(":x:").contains("needs a prompt");
	}

	@Test
	void theRequestersReplyBecomesThePromptOfTheSession() {
		runAwaiting("dev api feat/x");
		replies.clear();
		handler.continueDev(awaiting.get(0), "  Add a health endpoint\nand test it  ", replies::add);

		assertThat(starter.started).hasSize(1);
		SlackSessionStarter.StartRequest r = starter.started.get(0);
		assertThat(r.sessionType()).isEqualTo("development");
		assertThat(r.branch()).isEqualTo("feat/x");
		assertThat(r.baseBranch()).isEqualTo("main");
		assertThat(r.servicePath()).isEqualTo("/eco/api");
		assertThat(r.prompt()).isEqualTo("Add a health endpoint\nand test it");
		assertThat(r.slackUser()).isEqualTo("U1");
		assertThat(replies).hasSize(1);
		assertThat(replies.get(0)).startsWith(":rocket:").contains("`api`").contains("`feat/x`");
	}

	@Test
	void cancelAbandonsTheRequestWithoutStartingAnything() {
		runAwaiting("dev api feat/x");
		replies.clear();
		handler.continueDev(awaiting.get(0), " Cancel ", replies::add);
		assertThat(replies).hasSize(1);
		assertThat(replies.get(0)).contains("Cancelled").contains("nothing was started");
		assertThat(starter.started).isEmpty();
	}

	@Test
	void aFailureToStartAfterTheAnswerIsReportedInTheThread() {
		runAwaiting("dev api feat/x");
		replies.clear();
		starter.failStart = new IllegalStateException("max concurrent sessions reached (4)");
		handler.continueDev(awaiting.get(0), "do it", replies::add);
		assertThat(replies).hasSize(1);
		assertThat(replies.get(0)).startsWith(":x:").contains("max concurrent sessions reached (4)");
	}

	// ---------------------------------------------------------------- templates (review-with / dev-with)

	private SlackSessionStarter.TemplateInfo bugfix;

	private void templates() {
		bugfix = starter.template("bugfix", null);
		starter.template("Deep review", "develop");
	}

	@Test
	void devWithStartsFromTheTemplateAndSaysSo() {
		templates();
		String reply = run("dev-with bugfix api feat/x Fix the login bug");

		SlackSessionStarter.StartRequest r = starter.started.get(0);
		assertThat(r.templateId()).isEqualTo(bugfix.id());
		assertThat(r.sessionType()).isEqualTo("development");
		assertThat(r.prompt()).isEqualTo("Fix the login bug");
		assertThat(r.baseBranch()).isEqualTo("main"); // the template names none: the usual default
		assertThat(reply).startsWith(":rocket:").contains("Template `bugfix`");
	}

	@Test
	void aTemplateBaseBranchWinsForDevAsInTheDashboard() {
		templates();
		starter.defaultBase = "main";
		run("dev-with \"Deep review\" api feat/x go");
		assertThat(starter.started.get(0).baseBranch()).isEqualTo("develop");
	}

	@Test
	void reviewWithKeepsThePullRequestsOwnBaseBranchWhateverTheTemplateSays() {
		templates();
		String reply = run("review-with \"Deep review\" https://github.com/acme/api/pull/12");

		SlackSessionStarter.StartRequest r = starter.started.get(0);
		assertThat(r.sessionType()).isEqualTo("review");
		assertThat(r.baseBranch()).isEqualTo("main"); // the PR's, not the template's "develop"
		assertThat(r.templateId()).isNotNull();
		assertThat(reply).contains("Template `Deep review`");
	}

	@Test
	void theTemplateNameMatchesExactlyIgnoringCaseOtherwiseByUniquePrefix() {
		templates();
		run("dev-with BUGFIX api feat/a go");
		run("dev-with deep api feat/b go");
		assertThat(starter.started).extracting(r -> r.templateId()).hasSize(2).doesNotContainNull();
		assertThat(starter.started.get(0).templateId()).isEqualTo(bugfix.id());
		assertThat(starter.started.get(1).templateId()).isNotEqualTo(bugfix.id());
	}

	@Test
	void anUnknownOrAmbiguousTemplateListsWhatIsAvailableAndStartsNothing() {
		templates();
		assertThat(run("dev-with nope api feat/a go")).startsWith(":x:").contains("No template called `nope`")
				.contains("`bugfix`").contains("`Deep review`");
		starter.template("bugfix-strict", null);
		assertThat(run("dev-with bug api feat/a go")).contains("matches several templates").contains("`bugfix`").contains("`bugfix-strict`");
		assertThat(run("review-with nope acme/api#12")).contains("No template called");
		assertThat(starter.started).isEmpty();
		assertThat(starter.prLookups).isEmpty(); // a bad template is rejected before GitHub is asked anything
	}

	@Test
	void withoutAnyTemplateTheErrorSaysSoInsteadOfListingNothing() {
		assertThat(run("dev-with bugfix api feat/a go")).contains("There are no templates yet");
	}

	@Test
	void theTemplateIsCheckedBeforeTheService() {
		templates();
		assertThat(run("dev-with nope nosuchservice feat/a go")).contains("No template called").doesNotContain("No service called");
	}

	@Test
	void devWithAndNoPromptAsksForItAndRemembersTheTemplateForTheAnswer() {
		templates();
		String ask = runAwaiting("dev-with bugfix api feat/x");
		assertThat(ask).contains("template `bugfix`").contains("<@U1>");
		assertThat(awaiting.get(0).template().id()).isEqualTo(bugfix.id());

		replies.clear();
		handler.continueDev(awaiting.get(0), "Fix the NPE", replies::add);
		assertThat(starter.started).hasSize(1);
		assertThat(starter.started.get(0).templateId()).isEqualTo(bugfix.id());
		assertThat(starter.started.get(0).prompt()).isEqualTo("Fix the NPE");
	}

	@Test
	void helpListsTheTemplatesWhenThereAreAny() {
		assertThat(run("help")).doesNotContain("Templates:");
		templates();
		assertThat(run("help")).contains("review-with").contains("dev-with").contains("Templates: `Deep review`, `bugfix`");
	}

	// ---------------------------------------------------------------- help and surprises

	@Test
	void helpAndMistakesGetTheUsage() {
		assertThat(run("help")).contains("review <pull request URL>").contains("dev <service> <branch>");
		assertThat(run("")).contains("review <pull request URL>");
		assertThat(run("fly away")).startsWith(":x:").contains("fly").contains("review <pull request URL>");
		assertThat(run("dev api feat/x")).contains("needs a prompt");
		assertThat(starter.started).isEmpty();
	}

	@Test
	void anUnexpectedFailureBecomesAReplyNotAnException() {
		FakeStarter broken = new FakeStarter() {
			@Override
			public List<de.pamir.agentic.ui.git.ServiceCatalog.Service> services() {
				throw new IllegalStateException("ecosystem root vanished");
			}
		};
		List<String> out = new ArrayList<>();
		new SlackCommandHandler(broken, Runnable::run).handle("U1", "dev api feat/a go", out::add);
		assertThat(out).hasSize(1);
		assertThat(out.get(0)).startsWith(":x:").contains("ecosystem root vanished");
	}

	@Test
	void theWorkRunsOnTheGivenExecutorNotTheCallingThread() {
		List<Runnable> queued = new ArrayList<>();
		SlackCommandHandler async = new SlackCommandHandler(starter, queued::add);
		List<String> out = new ArrayList<>();
		async.handle("U1", PR12, out::add);
		assertThat(queued).hasSize(1);
		assertThat(out).isEmpty();
		assertThat(starter.started).isEmpty();
		queued.get(0).run();
		assertThat(out).hasSize(1);
		assertThat(starter.started).hasSize(1);
	}
}
