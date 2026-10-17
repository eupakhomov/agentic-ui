package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.git.GitOpsService.PrDetails;
import de.pamir.agentic.ui.git.GitOpsService.PrRef;
import de.pamir.agentic.ui.git.ServiceCatalog.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the Slack start commands need from the rest of the backend: the service list, a clone's
 * GitHub identity, a PR's details, and creating a session. An interface so {@link
 * SlackCommandHandler}'s decisions can be tested without git, {@code gh} or a database.
 */
interface SlackSessionStarter {

	/**
	 * @param prompt the first message, sent as soon as the session exists (queued until it is ready)
	 * @param prUrl  set for a review session, which is created against that PR
	 * @param templateId the template to start from, or null; its settings win over the inherited ones, its kickoff prompt is not used
	 */
	record StartRequest(String name, String branch, String baseBranch, String repoPath, String servicePath,
						String sessionType, String prUrl, String prompt, String slackUser, UUID templateId) {
	}

	/** A session template as far as Slack needs to know it; {@code baseBranch} is the template's own, if it sets one. */
	record TemplateInfo(UUID id, String name, String description, String baseBranch) {
	}

	record Started(UUID id, String name, String model, String permissionMode) {
	}

	List<Service> services();

	List<TemplateInfo> templates();

	/** {@code owner/repo} of the clone at {@code repoPath}, if its origin can be read. */
	Optional<String> originSlug(String repoPath);

	PrDetails viewPr(String anyRepoPath, PrRef ref);

	/** The branch a new development branch is cut from: main, else master, else the first branch. */
	String defaultBaseBranch(String repoPath);

	Started start(StartRequest request);
}
