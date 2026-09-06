package de.pamir.claude.ui.session;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Per-provider model catalog, mirroring each adapter's own {@code ready.capabilities.models}
 * (sidecar/src/protocol.ts's CLAUDE_CAPABILITIES, sidecar-codex/src/protocol.ts's
 * CODEX_CAPABILITIES — see docs/plan/phase-9-production-hardening.md P3). {@link ProviderController}
 * exposes this over {@code GET /api/providers} for the create dialog/template editor; {@link #byTier}
 * additionally lets backend-initiated system turns (system session default model, reflection,
 * service discovery, auto-titling) pick a model by role ("cheap"/"standard"/"premium") instead of
 * a hardcoded Claude alias, so they degrade sanely on a Codex-only install (see P1/P2).
 */
public final class ModelCatalog {

	private ModelCatalog() {
	}

	public record ModelInfo(String id, String label, String tier) {
	}

	private static final List<ModelInfo> CLAUDE_MODELS = List.of(
			new ModelInfo("haiku", "haiku", "cheap"),
			new ModelInfo("sonnet", "sonnet", "standard"),
			new ModelInfo("opus", "opus", "premium"));

	/**
	 * Codex resolves its own default model/alias with no fixed enumeration we can offer up front
	 * (docs/plan/phase-5.13-codex-provider.md: "no hardcoded Codex model list") — empty means the
	 * frontend falls back to a free-text model field, and {@link #byTier} always misses for it.
	 */
	private static final List<ModelInfo> CODEX_MODELS = List.of();

	private static final Map<String, List<ModelInfo>> BY_PROVIDER = Map.of(
			"claude", CLAUDE_MODELS,
			"codex", CODEX_MODELS);

	/** An unrecognized provider id falls back to Claude's catalog, matching ProviderController's capability fallback. */
	public static List<ModelInfo> models(String provider) {
		return BY_PROVIDER.getOrDefault(provider, CLAUDE_MODELS);
	}

	/**
	 * Resolves a tier name ("cheap"/"standard"/"premium") to a concrete model id for a provider.
	 * Empty when the provider has no fixed catalog (Codex) or no model of that tier — callers
	 * should treat that as "no override", same as an unspecified model today (the provider picks
	 * its own default).
	 */
	public static Optional<String> byTier(String provider, String tier) {
		String t = tier == null ? "" : tier.strip().toLowerCase(Locale.ROOT);
		return models(provider).stream().filter(m -> m.tier().equals(t)).map(ModelInfo::id).findFirst();
	}
}
