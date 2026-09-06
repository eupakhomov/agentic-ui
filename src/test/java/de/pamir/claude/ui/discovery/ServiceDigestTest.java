package de.pamir.claude.ui.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Filesystem-only, no Spring/DB — see docs/plan/phase-9-production-hardening.md T1. */
class ServiceDigestTest {

	@TempDir
	Path repo;

	@Test
	void rendersReadmeManifestAndAShallowListingSkippingNoiseDirs() throws Exception {
		Files.writeString(repo.resolve("README.md"), "# My Service\nDoes cool things.");
		Files.writeString(repo.resolve("package.json"), "{\"name\":\"my-service\",\"description\":\"a test service\"}");
		Files.createDirectories(repo.resolve("src"));
		Files.writeString(repo.resolve("src/index.js"), "console.log(1);");
		Files.createDirectories(repo.resolve("node_modules/some-dep"));
		Files.writeString(repo.resolve("node_modules/some-dep/pkg.json"), "{}");

		String digest = ServiceDigest.render(repo);

		assertThat(digest).contains("=== README.md ===");
		assertThat(digest).contains("Does cool things.");
		assertThat(digest).contains("package.json: my-service — a test service");
		assertThat(digest).contains("src/");
		assertThat(digest).contains("index.js");
		assertThat(digest).doesNotContain("node_modules");
	}

	@Test
	void truncatesDocFilesLongerThanTheCap() throws Exception {
		Files.writeString(repo.resolve("README.md"), "a".repeat(5_000));

		assertThat(ServiceDigest.render(repo)).contains("[truncated]");
	}

	@Test
	void fallsBackToPomXmlWhenNoPackageJsonIsPresent() throws Exception {
		Files.writeString(repo.resolve("pom.xml"),
				"<project><name>my-app</name><description>desc here</description></project>");

		assertThat(ServiceDigest.render(repo)).contains("pom.xml: my-app — desc here");
	}

	@Test
	void omitsManifestLineWhenNeitherManifestIsPresent() {
		assertThat(ServiceDigest.render(repo)).doesNotContain("package.json:").doesNotContain("pom.xml:");
	}
}
