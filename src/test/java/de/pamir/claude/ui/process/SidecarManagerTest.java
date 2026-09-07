package de.pamir.claude.ui.process;

import de.pamir.claude.ui.session.ProviderCapabilities;
import de.pamir.claude.ui.session.SessionEntity;
import de.pamir.claude.ui.session.SessionState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link SidecarManager#buildArgs} directly (package-private for this reason — see
 * its own comment) against a fabricated provider's capabilities, proving the flag-gating is
 * capability-driven rather than a hardcoded provider-name check (docs/plan/
 * phase-10-review-followups.md R1). buildArgs is defense-in-depth (SessionConfigFactory.prepare
 * is the primary enforcement point), so this only needs to prove the guard exists, not
 * duplicate every rejection case.
 */
class SidecarManagerTest {

	private static SessionEntity.Builder baseEntity() {
		return SessionEntity.builder().id(UUID.randomUUID()).name("s").repoPath("/repo")
				.branch("b").baseBranch("main").worktreePath("/wt").state(SessionState.CREATING)
				.allowedTools(List.of("Bash")).disallowedTools(List.of("Write"))
				.thinking("adaptive").maxTurns(7).fallbackModel("fallback-model");
	}

	private static de.pamir.claude.ui.session.ProviderCatalog catalogOf(String providerId, ProviderCapabilities caps) {
		return de.pamir.claude.ui.session.ProviderCatalog.fixedForTest(Map.of(providerId, caps));
	}

	@Test
	void omitsFlagsTheProviderDeclaresUnsupportedEvenWhenTheSessionCarriesValuesForThem() {
		ProviderCapabilities limited = new ProviderCapabilities(List.of("default"), false, true, false, true,
				true, false, true, true, false, false, true,
				List.of("allowedTools", "disallowedTools", "thinking", "maxTurns", "fallbackModel"), false, false);
		SidecarManager manager = new SidecarManager(null, null, catalogOf("widget", limited));
		SessionEntity entity = baseEntity().provider("widget").ecosystemPath("/ecosystem").build();

		List<String> args = manager.buildArgs(entity, null, false, null);

		assertThat(args).doesNotContain("--allowed-tools", "--disallowed-tools", "--thinking",
				"--max-turns", "--fallback-model", "--context-dir");
	}

	@Test
	void includesTheSameFlagsForAProviderThatSupportsThem() {
		ProviderCapabilities full = new ProviderCapabilities(List.of("default"), true, true, true, true, true,
				true, true, true, true, true, true, List.of(), true, true);
		SidecarManager manager = new SidecarManager(null, null, catalogOf("widget", full));
		SessionEntity entity = baseEntity().provider("widget").build();

		List<String> args = manager.buildArgs(entity, null, false, null);

		assertThat(args).contains("--allowed-tools", "--disallowed-tools", "--thinking",
				"--max-turns", "--fallback-model");
	}
}
