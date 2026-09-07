package de.pamir.claude.ui.session;

import de.pamir.claude.ui.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves each configured provider's {@link ProviderCapabilities} from its adapter package's
 * {@code capabilities.json} (a committed, build-generated mirror of the package's own
 * {@code *_CAPABILITIES} const — see {@code scripts/gen-provider-capabilities.mjs} and
 * docs/plan/phase-10-review-followups.md R1). Loaded lazily per provider id, not eagerly at
 * startup: a backend whose sidecar packages haven't all been {@code npm run build}'d yet (a
 * common state for a partial/fresh dev setup — see CLAUDE.md's "Sidecar" sections) should still
 * boot and serve the providers that ARE built; only using an unbuilt provider fails, with a
 * message naming exactly what's missing.
 */
@Component
public class ProviderCatalog {

	private final AppProperties props;
	private final ObjectMapper mapper;
	private final boolean fileBacked;
	private final Map<String, ProviderCapabilities> cache = new ConcurrentHashMap<>();

	// Explicit @Autowired: with the test-only constructor below also present, Spring can't apply
	// its usual "exactly one constructor" auto-detection, and silently falls back to a no-arg
	// constructor that doesn't exist — breaking application startup entirely, not just tests.
	@Autowired
	public ProviderCatalog(AppProperties props, ObjectMapper mapper) {
		this.props = props;
		this.mapper = mapper;
		this.fileBacked = true;
	}

	/** Test-only: bypasses file I/O entirely with a fixed, immutable map of fabricated capabilities. */
	ProviderCatalog(Map<String, ProviderCapabilities> fixed) {
		this.props = null;
		this.mapper = null;
		this.fileBacked = false;
		this.cache.putAll(fixed);
	}

	/**
	 * Public entry point to the same test-only constructor, for tests outside this package
	 * (e.g. {@code SidecarManagerTest}) that can't reach the package-private constructor directly.
	 */
	public static ProviderCatalog fixedForTest(Map<String, ProviderCapabilities> fixed) {
		return new ProviderCatalog(fixed);
	}

	public ProviderCapabilities get(String providerId) {
		ProviderCapabilities cached = cache.get(providerId);
		if (cached != null) {
			return cached;
		}
		if (!fileBacked) {
			throw new IllegalStateException("no capabilities declared for provider: " + providerId);
		}
		return cache.computeIfAbsent(providerId, this::load);
	}

	private ProviderCapabilities load(String providerId) {
		AppProperties.Provider provider = props.providers().get(providerId);
		if (provider == null) {
			throw new IllegalStateException("unknown provider: " + providerId);
		}
		Path file = capabilitiesFile(provider);
		if (!Files.exists(file)) {
			throw new IllegalStateException("provider '" + providerId + "': no capabilities.json at " + file
					+ " — run `npm run build` in its adapter package");
		}
		try {
			return mapper.readValue(file, ProviderCapabilities.class);
		} catch (RuntimeException e) {
			throw new IllegalStateException("provider '" + providerId + "': invalid capabilities.json at "
					+ file + ": " + e.getMessage(), e);
		}
	}

	/**
	 * {@code capabilities.json} sits next to the package's own {@code package.json}/{@code
	 * src/}, not inside the gitignored {@code dist/} its command actually launches (so it's a
	 * committed, diffable file) — derived by walking up one level when the launch script's
	 * immediate parent directory is named {@code dist}.
	 */
	private static Path capabilitiesFile(AppProperties.Provider provider) {
		String script = provider.command().stream()
				.filter(part -> part.endsWith(".js"))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"provider command has no .js entry point: " + provider.command()));
		Path dir = Path.of(script).toAbsolutePath().normalize().getParent();
		if (dir != null && "dist".equals(String.valueOf(dir.getFileName()))) {
			dir = dir.getParent();
		}
		return dir.resolve("capabilities.json");
	}
}
