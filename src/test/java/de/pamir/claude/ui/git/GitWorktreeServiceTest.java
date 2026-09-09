package de.pamir.claude.ui.git;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pickDefaultBranch's main/master preference — see docs/plan (Quick Session picking a stray
 * local branch like "ACC-1011-open-items-population" as the base when the repo has no "main"
 * and that branch happened to sort alphabetically before "master").
 */
class GitWorktreeServiceTest {

	@Test
	void prefersMainWhenPresent() {
		assertThat(GitWorktreeService.pickDefaultBranch(List.of("ACC-1011-open-items-population", "main", "master")))
				.isEqualTo("main");
	}

	@Test
	void fallsBackToMasterWhenNoMain() {
		assertThat(GitWorktreeService.pickDefaultBranch(List.of("ACC-1011-open-items-population", "master")))
				.isEqualTo("master");
	}

	@Test
	void fallsBackToTheFirstBranchWhenNeitherMainNorMasterExist() {
		assertThat(GitWorktreeService.pickDefaultBranch(List.of("develop", "feature/x")))
				.isEqualTo("develop");
	}

	@Test
	void fallsBackToMainForAnEmptyRepo() {
		assertThat(GitWorktreeService.pickDefaultBranch(List.of())).isEqualTo("main");
	}
}
