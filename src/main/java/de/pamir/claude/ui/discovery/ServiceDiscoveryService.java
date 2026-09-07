package de.pamir.claude.ui.discovery;

import de.pamir.claude.ui.concurrent.FireAndForget;
import de.pamir.claude.ui.concurrent.InFlightGuard;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitCommandRunner;
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
import java.util.List;
import java.util.UUID;

/**
 * Ecosystem service discovery (docs/plan/phase-8-service-discovery.md): regenerates a short
 * description + embedding for a service (git repo) when missing or stale, gated by the repo's own
 * git commit SHA rather than a re-read content hash (decision 4) — an unchanged repo costs one
 * {@code git rev-parse HEAD} call, nothing more, no digest read and no system turn.
 */
@Service
public class ServiceDiscoveryService {

	private static final Logger log = LoggerFactory.getLogger(ServiceDiscoveryService.class);
	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	private final SettingsService settings;
	private final SystemTurnClient systemTurnClient;
	private final GitCommandRunner git;
	private final ServiceProfileRepository profiles;
	private final EmbeddingClient embeddings;
	private final InFlightGuard<String> inFlight = new InFlightGuard<>();

	public ServiceDiscoveryService(SettingsService settings, SystemTurnClient systemTurnClient, GitCommandRunner git,
									ServiceProfileRepository profiles, EmbeddingClient embeddings) {
		this.settings = settings;
		this.systemTurnClient = systemTurnClient;
		this.git = git;
		this.profiles = profiles;
		this.embeddings = embeddings;
	}

	@EventListener
	public void onServiceDiscoveryRequested(ServiceDiscoveryRequested event) {
		FireAndForget.run("service-discovery-" + event.repoPath(), log,
				"service discovery failed for " + event.repoPath(), () -> discover(event.repoPath(), event.sessionId(), false));
	}

	/** Manual rediscover (dashboard "Rediscover" button / "Scan ecosystem now" loop) — always bypasses staleness. */
	public ServiceProfileRepository.ServiceProfile rediscover(String repoPath) {
		Path path = Path.of(repoPath);
		if (!Files.exists(path.resolve(".git"))) {
			throw new IllegalArgumentException("not a git repository: " + repoPath);
		}
		discover(repoPath, null, true);
		return profiles.findByRepoPath(repoPath)
				.orElseThrow(() -> new IllegalStateException("discovery did not produce a profile for " + repoPath));
	}

	/**
	 * Human hand-edit from the dashboard's service browser: writes the given description/tags
	 * directly (no system turn) and re-embeds them. Stamps the current commit SHA exactly like a
	 * real discovery would, so the edit participates correctly in the skip-logic above — it stands
	 * as an override until the repo's next commit, at which point auto-discovery is free to
	 * regenerate over it (same "editing is a veto, until content actually changes" posture as
	 * memory's hand-edited files).
	 */
	public ServiceProfileRepository.ServiceProfile updateDescription(String repoPath, String description,
																	  List<String> tags) {
		Path path = Path.of(repoPath);
		if (!Files.exists(path.resolve(".git"))) {
			throw new IllegalArgumentException("not a git repository: " + repoPath);
		}
		if (description == null || description.isBlank()) {
			throw new IllegalArgumentException("description must not be blank");
		}
		String name = path.getFileName().toString();
		String sha = currentCommitSha(path);
		var profile = profiles.upsert(repoPath, name, description.strip(), tags == null ? List.of() : tags, sha, null);
		embedBestEffort(repoPath, name, description.strip());
		return profile;
	}

	private void discover(String repoPath, UUID sessionId, boolean force) {
		if (!settings.serviceDiscoveryEnabled()) {
			return;
		}
		Path path = Path.of(repoPath);
		if (!Files.exists(path.resolve(".git"))) {
			return; // best-effort for the close-triggered path — the manual path validates up front
		}
		if (!inFlight.tryAcquire(repoPath)) {
			return; // another discovery for this exact repo is already running
		}
		try {
			var existing = profiles.findByRepoPath(repoPath);
			if (!force && existing.isPresent() && existing.get().discoveredAt()
					.isAfter(Instant.now().minus(settings.serviceDiscoveryStalenessDays(), ChronoUnit.DAYS))) {
				return;
			}
			String sha = currentCommitSha(path);
			if (existing.isPresent() && sha != null && sha.equals(existing.get().lastCommitSha())) {
				profiles.bumpDiscoveredAt(repoPath);
				return;
			}
			generate(path, repoPath, sessionId, sha);
		} finally {
			inFlight.release(repoPath);
		}
	}

	private String currentCommitSha(Path repoPath) {
		var result = git.run(repoPath, "rev-parse", "HEAD");
		return result.ok() ? result.stdout().strip() : null;
	}

	private void generate(Path path, String repoPath, UUID sessionId, String sha) {
		String digest = ServiceDigest.render(path);
		String name = path.getFileName().toString();
		String prompt = buildPrompt(name, digest);
		JsonNode result;
		try {
			String modelOverride = ModelCatalog.byTier(settings.systemProvider(), settings.serviceDiscoveryModel()).orElse(null);
			result = systemTurnClient.json(prompt, modelOverride, SystemTurnLane.BACKGROUND, TIMEOUT);
		} catch (RuntimeException e) {
			log.warn("service discovery failed for {}: {}", repoPath, e.getMessage());
			return;
		}
		String description = result.path("description").asText("").strip();
		if (description.isBlank()) {
			log.warn("service discovery response for {} had no description", repoPath);
			return;
		}
		List<String> tags = SystemTurnClient.lowercaseTags(result);
		profiles.upsert(repoPath, name, description, tags, sha, sessionId);
		embedBestEffort(repoPath, name, description);
	}

	private void embedBestEffort(String repoPath, String name, String description) {
		float[] vector = embeddings.tryEmbed(name + " " + description, false);
		if (vector != null) {
			profiles.upsertEmbedding(repoPath, vector, embeddings.model());
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
