package de.pamir.claude.ui.discovery;

import de.pamir.claude.ui.concurrent.FireAndForget;
import de.pamir.claude.ui.concurrent.InFlightGuard;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitWorktreeService;
import de.pamir.claude.ui.library.EmbeddingClient;
import de.pamir.claude.ui.session.ModelCatalog;
import de.pamir.claude.ui.session.SystemTurnClient;
import de.pamir.claude.ui.session.SystemTurnLane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ecosystem service discovery (docs/plan/phase-8-service-discovery.md): regenerates a short
 * description + embedding for a service (a git repo, or a monorepo package —
 * docs/plan/phase-11-monorepo.md) when missing or stale, gated by the last commit touching the
 * service's own subtree rather than a re-read content hash (decision 4/6) — an unchanged service
 * costs one {@code git log -1 -- <subtree>} call, nothing more, no digest read and no system turn.
 */
@Service
public class ServiceDiscoveryService {

	private static final Logger log = LoggerFactory.getLogger(ServiceDiscoveryService.class);
	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	private final SettingsService settings;
	private final SystemTurnClient systemTurnClient;
	private final GitWorktreeService worktrees;
	private final ServiceProfileRepository profiles;
	private final EmbeddingClient embeddings;
	private final InFlightGuard<String> inFlight = new InFlightGuard<>();

	public ServiceDiscoveryService(SettingsService settings, SystemTurnClient systemTurnClient,
									GitWorktreeService worktrees, ServiceProfileRepository profiles,
									EmbeddingClient embeddings) {
		this.settings = settings;
		this.systemTurnClient = systemTurnClient;
		this.worktrees = worktrees;
		this.profiles = profiles;
		this.embeddings = embeddings;
	}

	@EventListener
	public void onServiceDiscoveryRequested(ServiceDiscoveryRequested event) {
		FireAndForget.run("service-discovery-" + event.servicePath(), log,
				"service discovery failed for " + event.servicePath(),
				() -> discover(event.servicePath(), event.repoPath(), event.sessionId(), false));
	}

	/** Manual rediscover (dashboard "Rediscover" button / "Scan ecosystem now" loop) — always bypasses staleness. */
	public ServiceProfileRepository.ServiceProfile rediscover(String servicePath) {
		Path repoRoot = resolveKnownService(servicePath)
				.orElseThrow(() -> new IllegalArgumentException("not a known service: " + servicePath));
		discover(servicePath, repoRoot.toString(), null, true);
		return profiles.findByServicePath(servicePath)
				.orElseThrow(() -> new IllegalStateException("discovery did not produce a profile for " + servicePath));
	}

	/**
	 * Human hand-edit from the dashboard's service browser: writes the given description/tags
	 * directly (no system turn) and re-embeds them. Stamps the current subtree SHA exactly like a
	 * real discovery would, so the edit participates correctly in the skip-logic above — it stands
	 * as an override until the service's next commit, at which point auto-discovery is free to
	 * regenerate over it (same "editing is a veto, until content actually changes" posture as
	 * memory's hand-edited files).
	 */
	public ServiceProfileRepository.ServiceProfile updateDescription(String servicePath, String description,
																	  List<String> tags) {
		Path repoRoot = resolveKnownService(servicePath)
				.orElseThrow(() -> new IllegalArgumentException("not a known service: " + servicePath));
		if (description == null || description.isBlank()) {
			throw new IllegalArgumentException("description must not be blank");
		}
		String name = Path.of(servicePath).getFileName().toString();
		String sha = worktrees.lastCommitTouching(repoRoot, Path.of(servicePath));
		var profile = profiles.upsert(servicePath, repoRoot.toString(), name, description.strip(),
				tags == null ? List.of() : tags, sha, null);
		embedBestEffort(servicePath, name, description.strip());
		return profile;
	}

	/**
	 * {@code servicePath} resolves to a repo (via {@link GitWorktreeService#repoRootOf}) and is a
	 * known service under the configured ecosystem root — the repo root itself, or one of {@link
	 * GitWorktreeService#findServices}'s results (docs/plan/phase-11-monorepo.md Step 5). Empty if
	 * either check fails.
	 */
	private Optional<Path> resolveKnownService(String servicePath) {
		Path service = Path.of(servicePath).toAbsolutePath().normalize();
		Optional<Path> repoRoot = worktrees.repoRootOf(service);
		if (repoRoot.isEmpty()) {
			return Optional.empty();
		}
		String ecosystemRoot = settings.current().ecosystemRoot();
		Path eco = ecosystemRoot.isBlank() ? null : Path.of(ecosystemRoot);
		if (!worktrees.isKnownService(eco, monorepoGlobs(), repoRoot.get(), service)) {
			return Optional.empty();
		}
		return repoRoot;
	}

	private List<String> monorepoGlobs() {
		return Arrays.stream(settings.current().monorepoServiceGlobs().split(","))
				.map(String::strip).filter(g -> !g.isEmpty()).toList();
	}

	private void discover(String servicePath, String repoPath, UUID sessionId, boolean force) {
		if (!settings.current().serviceDiscoveryEnabled()) {
			return;
		}
		Path repoRoot = Path.of(repoPath);
		if (!Files.exists(repoRoot.resolve(".git"))) {
			return; // best-effort for the close-triggered path — the manual path validates up front
		}
		if (!inFlight.tryAcquire(servicePath)) {
			return; // another discovery for this exact service is already running
		}
		try {
			var existing = profiles.findByServicePath(servicePath);
			if (!force && existing.isPresent() && existing.get().discoveredAt()
					.isAfter(Instant.now().minus(settings.current().serviceDiscoveryStalenessDays(), ChronoUnit.DAYS))) {
				return;
			}
			String sha = worktrees.lastCommitTouching(repoRoot, Path.of(servicePath));
			if (existing.isPresent() && sha != null && sha.equals(existing.get().lastCommitSha())) {
				profiles.bumpDiscoveredAt(servicePath);
				return;
			}
			generate(Path.of(servicePath), repoRoot, servicePath, sessionId, sha);
		} finally {
			inFlight.release(servicePath);
		}
	}

	private void generate(Path servicePathDir, Path repoRoot, String servicePath, UUID sessionId, String sha) {
		String digest = ServiceDigest.render(servicePathDir);
		String name = servicePathDir.getFileName().toString();
		String prompt = buildPrompt(name, digest);
		JsonNode result;
		try {
			String modelOverride = ModelCatalog.byTier(settings.systemProvider(), settings.current().serviceDiscoveryModel()).orElse(null);
			result = systemTurnClient.json(prompt, modelOverride, SystemTurnLane.BACKGROUND, TIMEOUT);
		} catch (RuntimeException e) {
			log.warn("service discovery failed for {}: {}", servicePath, e.getMessage());
			return;
		}
		String description = result.path("description").asText("").strip();
		if (description.isBlank()) {
			log.warn("service discovery response for {} had no description", servicePath);
			return;
		}
		List<String> tags = SystemTurnClient.lowercaseTags(result);
		profiles.upsert(servicePath, repoRoot.toString(), name, description, tags, sha, sessionId);
		embedBestEffort(servicePath, name, description);
	}

	private void embedBestEffort(String servicePath, String name, String description) {
		float[] vector = embeddings.tryEmbed(name + " " + description, false);
		if (vector != null) {
			profiles.upsertEmbedding(servicePath, vector, embeddings.model());
		}
	}

	private static String buildPrompt(String name, String digest) {
		return """
				You are cataloguing a service in a multi-service ecosystem so other services' coding
				agents can quickly learn what it does and where to look. Given the digest below (README/
				docs, a manifest sniff, and a shallow directory listing) for the service "%s", respond with
				ONLY a JSON object — no markdown fences, no commentary — of the form {"description": "3-8
				sentences covering the service's purpose, its main subsystems/responsibilities, and WHERE
				they live (real paths from the listing) — specific enough to answer 'where is X
				implemented'", "tags": ["3-6 short lowercase keyword tags"]}.

				%s
				""".formatted(name, digest);
	}

}
