package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.git.GitOpsService.PrDetails;
import de.pamir.agentic.ui.git.GitOpsService.PrInfo;
import de.pamir.agentic.ui.git.GitOpsService.PrRef;
import de.pamir.agentic.ui.git.ServiceCatalog.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Scriptable {@link SlackSessionStarter}: a service list, clone identities, PR details and a record of what was started. */
class FakeStarter implements SlackSessionStarter {

	final List<Service> services = new ArrayList<>();
	final Map<String, String> origins = new HashMap<>();          // repoPath -> owner/repo
	final Map<Integer, PrDetails> prs = new HashMap<>();
	final List<TemplateInfo> templates = new ArrayList<>();
	final List<StartRequest> started = Collections.synchronizedList(new ArrayList<>());
	final List<String> prLookups = new ArrayList<>();             // repoPath each PR was looked up from
	String defaultBase = "main";
	RuntimeException failStart;
	RuntimeException failView;
	String model = "haiku";
	String permissionMode = "default";

	FakeStarter polyrepo(String name, String origin) {
		String path = "/eco/" + name;
		services.add(new Service(name, path, path, false));
		origins.put(path, origin);
		return this;
	}

	FakeStarter monorepoPackage(String repoName, String pkg, String origin) {
		String repo = "/eco/" + repoName;
		services.add(new Service(pkg, repo + "/packages/" + pkg, repo, true));
		origins.put(repo, origin);
		return this;
	}

	TemplateInfo template(String name, String baseBranch) {
		TemplateInfo t = new TemplateInfo(UUID.randomUUID(), name, "", baseBranch);
		templates.add(t);
		return t;
	}

	static PrDetails pr(int number, String state, boolean fork, String... files) {
		return new PrDetails(new PrInfo(number, "Fix the thing | now", "feat/fix-" + number, "main",
				"https://github.com/acme/api/pull/" + number, "alice", false), state, fork, List.of(files));
	}

	@Override
	public List<Service> services() {
		return services;
	}

	@Override
	public List<TemplateInfo> templates() {
		return templates;
	}

	@Override
	public Optional<String> originSlug(String repoPath) {
		return Optional.ofNullable(origins.get(repoPath));
	}

	@Override
	public PrDetails viewPr(String anyRepoPath, PrRef ref) {
		prLookups.add(anyRepoPath);
		if (failView != null) {
			throw failView;
		}
		PrDetails d = prs.get(ref.number());
		if (d == null) {
			throw new IllegalStateException("gh pr view failed: no pull request found");
		}
		return d;
	}

	@Override
	public String defaultBaseBranch(String repoPath) {
		return defaultBase;
	}

	@Override
	public Started start(StartRequest request) {
		if (failStart != null) {
			throw failStart;
		}
		started.add(request);
		return new Started(UUID.randomUUID(), request.name(), model, permissionMode);
	}
}
