package de.pamir.claude.ui.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure text parsing, no Spring/DB — see docs/plan/phase-9-production-hardening.md T1. */
class FrontmatterTest {

	@Test
	void rendersAndReparsesAServiceScopedDocRoundTrip() {
		String rendered = Frontmatter.render("my-name", "a description", List.of("tag1", "tag2"), "service",
				"/path/to/repo", "2026-09-06T12:00:00Z", "Some body text.");

		Frontmatter.Doc doc = Frontmatter.parse(rendered);

		assertThat(doc.meta()).containsEntry("name", "my-name");
		assertThat(doc.meta()).containsEntry("description", "a description");
		assertThat(doc.meta()).containsEntry("tags", List.of("tag1", "tag2"));
		assertThat(doc.meta()).containsEntry("scope", "service");
		assertThat(doc.meta()).containsEntry("service", "/path/to/repo");
		assertThat(doc.meta()).containsEntry("updated", "2026-09-06T12:00:00Z");
		assertThat(doc.body().strip()).isEqualTo("Some body text.");
	}

	@Test
	void omitsTheServiceKeyForEcosystemScope() {
		String rendered = Frontmatter.render("n", "d", List.of(), "ecosystem", null, "2026-01-01", "body");

		Frontmatter.Doc doc = Frontmatter.parse(rendered);

		assertThat(doc.meta()).doesNotContainKey("service");
		assertThat(doc.meta()).containsEntry("scope", "ecosystem");
	}

	@Test
	void treatsContentWithNoFrontmatterFenceAsPlainBody() {
		Frontmatter.Doc doc = Frontmatter.parse("Just plain text, no frontmatter.");

		assertThat(doc.meta()).isEmpty();
		assertThat(doc.body()).isEqualTo("Just plain text, no frontmatter.");
	}

	@Test
	void treatsAnUnterminatedFenceAsPlainBody() {
		String content = "---\nname: x\n(no closing fence)";

		Frontmatter.Doc doc = Frontmatter.parse(content);

		assertThat(doc.meta()).isEmpty();
		assertThat(doc.body()).isEqualTo(content);
	}

	@Test
	void normalizesCrlfLineEndingsBeforeParsing() {
		String rendered = Frontmatter.render("n", "d", List.of("t"), "service", "/repo", "2026-01-01", "body")
				.replace("\n", "\r\n");

		Frontmatter.Doc doc = Frontmatter.parse(rendered);

		assertThat(doc.meta()).containsEntry("name", "n");
		assertThat(doc.body().strip()).isEqualTo("body");
	}
}
