package de.pamir.claude.ui.web;

import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.discovery.ServiceDiscoveryService;
import de.pamir.claude.ui.discovery.ServiceProfileRepository;
import de.pamir.claude.ui.git.GitWorktreeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

/**
 * Human-facing service-discovery API (docs/plan/phase-8-service-discovery.md): the dashboard's
 * service browser and its Rediscover / "Scan ecosystem now" actions. Agent access goes through
 * {@code ServiceDiscoveryMcpTools} instead, not here.
 */
@RestController
@RequestMapping("/api/service-discovery")
public class ServiceDiscoveryController {

	public record ServiceView(String name, String servicePath, String description, List<String> tags,
							   Instant discoveredAt, boolean stale) {
	}

	public record RediscoverRequest(String servicePath) {
	}

	public record UpdateDescriptionRequest(String servicePath, String description, List<String> tags) {
	}

	private final SettingsService settings;
	private final GitWorktreeService worktrees;
	private final ServiceProfileRepository profiles;
	private final ServiceDiscoveryService discovery;

	public ServiceDiscoveryController(SettingsService settings, GitWorktreeService worktrees,
									   ServiceProfileRepository profiles, ServiceDiscoveryService discovery) {
		this.settings = settings;
		this.worktrees = worktrees;
		this.profiles = profiles;
		this.discovery = discovery;
	}

	/** Every ecosystem service (git repos, or a monorepo's packages), left-joined against its discovery profile — never-discovered ones show up too. */
	@GetMapping("/services")
	public List<ServiceView> services() {
		String ecosystemRoot = settings.current().ecosystemRoot();
		List<GitWorktreeService.ServiceInfo> known =
				ecosystemRoot.isBlank() ? List.of() : worktrees.findServices(Path.of(ecosystemRoot), monorepoGlobs());
		Instant staleBefore = Instant.now().minus(settings.current().serviceDiscoveryStalenessDays(), ChronoUnit.DAYS);
		return known.stream().map(svc -> {
			var profile = profiles.findByServicePath(svc.servicePath());
			if (profile.isEmpty()) {
				return new ServiceView(svc.name(), svc.servicePath(), null, List.of(), null, true);
			}
			var p = profile.get();
			return new ServiceView(p.name(), p.servicePath(), p.description(), p.tags(), p.discoveredAt(),
					p.discoveredAt().isBefore(staleBefore));
		}).toList();
	}

	/** Forces a fresh discovery for one service regardless of staleness (still skips the LLM call if the subtree SHA is unchanged). */
	@PostMapping("/services/rediscover")
	public ServiceView rediscover(@RequestBody RediscoverRequest request) {
		var p = discovery.rediscover(request.servicePath());
		return new ServiceView(p.name(), p.servicePath(), p.description(), p.tags(), p.discoveredAt(), false);
	}

	/** Human hand-edit of a service's description/tags — writes directly (no LLM call) and re-embeds. */
	@PatchMapping("/services")
	public ServiceView updateDescription(@RequestBody UpdateDescriptionRequest request) {
		var p = discovery.updateDescription(request.servicePath(), request.description(), request.tags());
		return new ServiceView(p.name(), p.servicePath(), p.description(), p.tags(), p.discoveredAt(), false);
	}

	private List<String> monorepoGlobs() {
		return Arrays.stream(settings.current().monorepoServiceGlobs().split(","))
				.map(String::strip).filter(g -> !g.isEmpty()).toList();
	}
}
