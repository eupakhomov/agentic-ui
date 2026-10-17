package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.git.GitOpsService.PrDetails;
import de.pamir.agentic.ui.git.GitOpsService.PrRef;
import de.pamir.agentic.ui.git.ServiceCatalog.Service;
import de.pamir.agentic.ui.slack.SlackSessionStarter.StartRequest;
import de.pamir.agentic.ui.slack.SlackSessionStarter.Started;
import de.pamir.agentic.ui.slack.SlackSessionStarter.TemplateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Turns a parsed {@code @bot review …} / {@code @bot dev …} request into a started session, or into
 * a plain explanation of why not. Everything that can be decided without Slack lives here: which local
 * service a pull request belongs to, which service a name means, whether a branch name is sane.
 *
 * <p>The review path is deliberately strict: the PR's repository must match exactly one local clone;
 * the PR must be open and from the same repository (a fork's head branch does not exist on {@code
 * origin}); and in a monorepo its changed files must fall in exactly one package. Anything else is
 * reported, not guessed.
 *
 * <p>Starting a session takes seconds (worktree, fetch, provisioning), so {@link #handle} runs on the
 * given executor and never blocks the caller; the outcome comes back through the {@link Replier}.
 */
final class SlackCommandHandler {

	private static final Logger log = LoggerFactory.getLogger(SlackCommandHandler.class);

	/** Where the answer goes: a message in the thread of the request. mrkdwn, already escaped. */
	@FunctionalInterface
	interface Replier {
		void reply(String mrkdwn);
	}

	/** A {@code dev} request that is only waiting for its prompt: the service and branch have already been checked. */
	record AwaitedPrompt(String user, Service service, String branch, TemplateInfo template) {
	}

	private static final Pattern SAFE_BRANCH = Pattern.compile("[A-Za-z0-9._/-]+");
	private static final int LISTED_NAMES = 8;

	private final SlackSessionStarter starter;
	private final Executor executor;

	SlackCommandHandler(SlackSessionStarter starter, Executor executor) {
		this.starter = starter;
		this.executor = executor;
	}

	/** Runs slow work (closing a session takes seconds) off the caller's thread, on the same executor as the commands. */
	void runInBackground(Runnable work) {
		executor.execute(work);
	}

	/** As {@link #handle(String, String, Replier, Consumer)} for a caller that cannot wait for a follow-up message. */
	void handle(String slackUser, String text, Replier replier) {
		handle(slackUser, text, replier, null);
	}

	/**
	 * @param text        what followed the mention, unescaped from Slack's encoding
	 * @param awaitPrompt told about a {@code dev} request that has no prompt yet, so the caller can route the
	 *                    requester's next reply to {@link #continueDev}; null = it cannot, and a missing prompt is an error
	 */
	void handle(String slackUser, String text, Replier replier, Consumer<AwaitedPrompt> awaitPrompt) {
		executor.execute(() -> {
			try {
				run(slackUser, SlackStartCommand.parse(text), replier, awaitPrompt);
			} catch (RuntimeException e) {
				log.warn("slack: start command failed: {}", e.getMessage());
				replier.reply(error("Could not start the session — " + firstLine(e.getMessage())));
			}
		});
	}

	private void run(String user, SlackStartCommand command, Replier replier, Consumer<AwaitedPrompt> awaitPrompt) {
		switch (command) {
			case SlackStartCommand.Help h -> replier.reply(SlackStartCommand.USAGE + (templatesLine().isEmpty() ? "" : "\n" + templatesLine().strip()));
			case SlackStartCommand.Invalid i -> replier.reply(error(i.reason()) + "\n" + SlackStartCommand.USAGE);
			case SlackStartCommand.Review r -> review(user, r, replier);
			case SlackStartCommand.Dev d -> dev(user, d, replier, awaitPrompt);
		}
	}

	// ------------------------------------------------------------------ review

	private void review(String user, SlackStartCommand.Review command, Replier replier) {
		TemplateInfo template = null;
		if (command.template() != null) {
			template = resolveTemplate(command.template(), replier);
			if (template == null) {
				return;
			}
		}
		PrRef ref = command.pr();
		String slug = ref.owner() + "/" + ref.repo();
		List<Service> all = starter.services();
		Map<String, Boolean> matchesByRepo = new HashMap<>();
		List<Service> candidates = all.stream()
				.filter(s -> matchesByRepo.computeIfAbsent(s.repoPath(),
						repo -> starter.originSlug(repo).map(slug::equalsIgnoreCase).orElse(false)))
				.toList();
		if (candidates.isEmpty()) {
			replier.reply(error("No local service is a clone of `" + esc(slug) + "`." + known(all)));
			return;
		}
		Set<String> repos = new LinkedHashSet<>();
		candidates.forEach(c -> repos.add(c.repoPath()));
		if (repos.size() > 1) {
			replier.reply(error("`" + esc(slug) + "` is cloned more than once (" + String.join(", ", repos.stream().map(SlackBlocks::esc).toList())
					+ "), so I can't tell which one to review in."));
			return;
		}
		PrDetails details;
		try {
			details = starter.viewPr(candidates.get(0).repoPath(), ref);
		} catch (RuntimeException e) {
			replier.reply(error("Could not read " + esc(slug) + "#" + ref.number() + " from GitHub — " + esc(firstLine(e.getMessage()))));
			return;
		}
		if (!"OPEN".equalsIgnoreCase(details.state())) {
			replier.reply(error("Pull request #" + ref.number() + " is " + details.state().toLowerCase(Locale.ROOT)
					+ " — only open pull requests can be reviewed."));
			return;
		}
		if (details.crossRepository()) {
			replier.reply(error("Pull request #" + ref.number() + " comes from a fork, whose branch is not in the local clone. "
					+ "Reviewing fork PRs is not supported yet."));
			return;
		}
		Service service = pickPackage(candidates, details.files(), ref, replier);
		if (service == null) {
			return;
		}
		String branch = details.pr().headRefName();
		Started started = starter.start(new StartRequest("review: " + branch, branch, details.pr().baseRefName(),
				service.repoPath(), service.path(), "review", details.pr().url(), reviewPrompt(details, command.extra()), user,
				template == null ? null : template.id()));
		replier.reply(":mag: Review session *" + esc(started.name()) + "* started for <" + details.pr().url() + "|#" + ref.number()
				+ " " + esc(details.pr().title()).replace("|", "/") + "> in `" + esc(service.name()) + "`." + usingTemplate(template) + settings(started)
				+ " It has its own thread in this channel.");
	}

	/**
	 * One candidate is the answer. Several (the packages of one monorepo) are told apart by the PR's
	 * changed files: the package whose folder contains a file owns it, the deepest folder winning, and
	 * the PR must resolve to exactly one package.
	 */
	private Service pickPackage(List<Service> candidates, List<String> files, PrRef ref, Replier replier) {
		if (candidates.size() == 1) {
			return candidates.get(0);
		}
		Set<Service> touched = new LinkedHashSet<>();
		for (String file : files) {
			Service owner = null;
			int ownerDepth = -1;
			for (Service candidate : candidates) {
				String folder = relativeFolder(candidate);
				boolean contains = folder.isEmpty() || file.equals(folder) || file.startsWith(folder + "/");
				if (contains && folder.length() > ownerDepth) {
					owner = candidate;
					ownerDepth = folder.length();
				}
			}
			if (owner != null) {
				touched.add(owner);
			}
		}
		if (touched.size() == 1) {
			return touched.iterator().next();
		}
		String packages = String.join(", ", candidates.stream().map(c -> "`" + esc(c.name()) + "`").toList());
		if (touched.isEmpty()) {
			replier.reply(error("Pull request #" + ref.number() + " changes no files inside any package I know in that repository ("
					+ packages + ")."));
		} else {
			replier.reply(error("Pull request #" + ref.number() + " touches several packages ("
					+ String.join(", ", touched.stream().map(c -> "`" + esc(c.name()) + "`").toList())
					+ "). A review session runs in one package, so I won't pick one for you."));
		}
		return null;
	}

	private static String relativeFolder(Service service) {
		if (service.path().equals(service.repoPath())) {
			return "";
		}
		return Path.of(service.repoPath()).relativize(Path.of(service.path())).toString().replace('\\', '/');
	}

	static String reviewPrompt(PrDetails details, String extra) {
		StringBuilder sb = new StringBuilder("Review pull request #").append(details.pr().number()).append(" \"")
				.append(details.pr().title()).append("\" (").append(details.pr().url()).append(")");
		if (!details.pr().author().isBlank()) {
			sb.append(" by ").append(details.pr().author());
		}
		sb.append(".\nIts head branch `").append(details.pr().headRefName()).append("` is checked out in your worktree and it will merge into `")
				.append(details.pr().baseRefName()).append("`. Start by reading the diff against the base branch, then look for correctness bugs, "
						+ "risky or incomplete changes, missing tests and unclear code, and say what you checked. When you are done, summarise your findings "
						+ "and submit the review with the submit_pr_review tool (you will be asked for approval before it posts).");
		if (extra != null && !extra.isBlank()) {
			sb.append("\n\nAdditional instructions from the requester: ").append(extra.strip());
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ dev

	private void dev(String user, SlackStartCommand.Dev command, Replier replier, Consumer<AwaitedPrompt> awaitPrompt) {
		TemplateInfo template = null;
		if (command.template() != null) {
			template = resolveTemplate(command.template(), replier);
			if (template == null) {
				return;
			}
		}
		List<Service> all = starter.services();
		Service service = resolveService(command.service(), all, replier);
		if (service == null) {
			return;
		}
		String branch = command.branch();
		String problem = branchProblem(branch);
		if (problem != null) {
			replier.reply(error("`" + esc(branch) + "` is not a usable branch name — " + problem + "."));
			return;
		}
		if (command.prompt().isBlank()) {
			if (awaitPrompt == null) {
				replier.reply(error("`dev` needs a prompt after the branch \u2014 what should the session do?"));
				return;
			}
			// service and branch are already known to be fine, so a typo is caught now rather than after the wait
			awaitPrompt.accept(new AwaitedPrompt(user, service, branch, template));
			replier.reply(":speech_balloon: <@" + user + "> what should the session do on `" + esc(service.name()) + "` (branch `" + esc(branch)
					+ "`" + (template == null ? "" : ", template `" + esc(template.name()) + "`")
					+ ")? Reply in this thread with the prompt, or `cancel`. I will wait 15 minutes.");
			return;
		}
		startDev(user, service, branch, template, command.prompt(), replier);
	}

	/** The requester answered the question {@code dev} asked: their reply is the prompt (or {@code cancel}). */
	void continueDev(AwaitedPrompt awaited, String reply, Replier replier) {
		executor.execute(() -> {
			try {
				if (reply.strip().equalsIgnoreCase("cancel")) {
					replier.reply(":white_check_mark: Cancelled \u2014 nothing was started.");
					return;
				}
				startDev(awaited.user(), awaited.service(), awaited.branch(), awaited.template(), reply.strip(), replier);
			} catch (RuntimeException e) {
				log.warn("slack: start command failed: {}", e.getMessage());
				replier.reply(error("Could not start the session \u2014 " + firstLine(e.getMessage())));
			}
		});
	}

	private void startDev(String user, Service service, String branch, TemplateInfo template, String prompt, Replier replier) {
		// a template that names a base branch wins, as in the dashboard; otherwise main, else master, else the first branch
		String base = template != null && template.baseBranch() != null && !template.baseBranch().isBlank()
				? template.baseBranch() : starter.defaultBaseBranch(service.repoPath());
		Started started = starter.start(new StartRequest(branch, branch, base, service.repoPath(), service.path(),
				"development", null, prompt, user, template == null ? null : template.id()));
		replier.reply(":rocket: Session *" + esc(started.name()) + "* started on `" + esc(service.name()) + "` (branch `" + esc(branch)
				+ "` from `" + esc(base) + "`)." + usingTemplate(template) + settings(started) + " It has its own thread in this channel.");
	}

	/** Exact name (any case), else the one template whose name starts with it; otherwise say what is available. */
	private TemplateInfo resolveTemplate(String wanted, Replier replier) {
		List<TemplateInfo> all = starter.templates();
		String w = wanted.toLowerCase(Locale.ROOT);
		List<TemplateInfo> exact = all.stream().filter(t -> t.name().toLowerCase(Locale.ROOT).equals(w)).toList();
		List<TemplateInfo> matches = exact.isEmpty()
				? all.stream().filter(t -> t.name().toLowerCase(Locale.ROOT).startsWith(w)).toList() : exact;
		if (matches.size() == 1) {
			return matches.get(0);
		}
		if (matches.isEmpty()) {
			replier.reply(error("No template called `" + esc(wanted) + "`." + (all.isEmpty() ? " There are no templates yet (create one in the dashboard)." : templatesLine())));
		} else {
			replier.reply(error("`" + esc(wanted) + "` matches several templates: "
					+ String.join(", ", matches.stream().map(m -> "`" + esc(m.name()) + "`").toList()) + ". Use the full name."));
		}
		return null;
	}

	private String templatesLine() {
		List<String> names = starter.templates().stream().map(TemplateInfo::name).sorted().toList();
		if (names.isEmpty()) {
			return "";
		}
		String shown = String.join(", ", names.stream().limit(LISTED_NAMES).map(n -> "`" + esc(n) + "`").toList());
		return " Templates: " + shown + (names.size() > LISTED_NAMES ? ", \u2026" : "") + ".";
	}

	private static String usingTemplate(TemplateInfo template) {
		return template == null ? "" : " Template `" + esc(template.name()) + "`.";
	}

	/** Exact name (any case), else the one service whose name starts with it; otherwise say what is available. */
	private Service resolveService(String wanted, List<Service> all, Replier replier) {
		String w = wanted.toLowerCase(Locale.ROOT);
		List<Service> exact = all.stream().filter(s -> s.name().toLowerCase(Locale.ROOT).equals(w)).toList();
		List<Service> matches = exact.isEmpty()
				? all.stream().filter(s -> s.name().toLowerCase(Locale.ROOT).startsWith(w)).toList() : exact;
		if (matches.size() == 1) {
			return matches.get(0);
		}
		if (matches.isEmpty()) {
			replier.reply(error("No service called `" + esc(wanted) + "`." + known(all)));
		} else {
			replier.reply(error("`" + esc(wanted) + "` matches several services: "
					+ String.join(", ", matches.stream().map(m -> "`" + esc(m.name()) + "` (" + esc(m.path()) + ")").toList())
					+ ". Use the full name or ask for a more specific one."));
		}
		return null;
	}

	/** Why this cannot be a branch name, or null when it looks fine. Git has the last word on the rest. */
	static String branchProblem(String branch) {
		if (!SAFE_BRANCH.matcher(branch).matches()) {
			return "use only letters, digits, `.`, `_`, `-` and `/`";
		}
		if (branch.startsWith("-") || branch.startsWith("/") || branch.startsWith(".")) {
			return "it cannot start with `-`, `/` or `.`";
		}
		if (branch.contains("..") || branch.contains("//") || branch.endsWith("/") || branch.endsWith(".") || branch.endsWith(".lock")) {
			return "it is not a valid git ref";
		}
		return null;
	}

	// ------------------------------------------------------------------ text

	/** What the new session inherited, so nobody is surprised by it. */
	private static String settings(Started started) {
		List<String> parts = new ArrayList<>();
		if (started.model() != null) {
			parts.add("model " + esc(started.model()));
		}
		if (started.permissionMode() != null) {
			parts.add("permissions: " + esc(started.permissionMode()));
		}
		return parts.isEmpty() ? "" : " (" + String.join(" · ", parts) + ")";
	}

	private static String known(List<Service> all) {
		List<String> names = all.stream().map(Service::name).distinct().sorted().toList();
		if (names.isEmpty()) {
			return " No services are configured (check the ecosystem root in Settings).";
		}
		String shown = String.join(", ", names.stream().limit(LISTED_NAMES).map(n -> "`" + esc(n) + "`").toList());
		return " Services I know: " + shown + (names.size() > LISTED_NAMES ? ", …" : "") + ".";
	}

	private static String error(String text) {
		return ":x: " + text;
	}

	private static String esc(String s) {
		return SlackBlocks.esc(s);
	}

	private static String firstLine(String message) {
		String line = message == null ? "" : message.lines().findFirst().orElse("");
		return line.length() > 300 ? line.substring(0, 300) + "…" : line;
	}
}
