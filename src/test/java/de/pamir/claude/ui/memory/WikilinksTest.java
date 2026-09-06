package de.pamir.claude.ui.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure regex extraction, no Spring/DB — see docs/plan/phase-9-production-hardening.md T1. */
class WikilinksTest {

	@Test
	void extractsSlugsInFirstAppearanceOrderAndDedupes() {
		String body = "See [[foo-bar]] and [[baz|Baz Alias]] for details. Duplicate: [[foo-bar]].";

		assertThat(Wikilinks.extract(body)).containsExactly("foo-bar", "baz");
	}

	@Test
	void ignoresLinksInsideFencedCodeBlocks() {
		String body = "Real link [[keep-me]].\n```\nFake [[ignored-in-code]] link\n```\nAnother [[also-keep]]";

		assertThat(Wikilinks.extract(body)).containsExactly("keep-me", "also-keep");
	}

	@Test
	void trimsWhitespaceAroundTheSlug() {
		assertThat(Wikilinks.extract("[[  spaced-slug  ]]")).containsExactly("spaced-slug");
	}

	@Test
	void returnsEmptyForNullOrBlankBody() {
		assertThat(Wikilinks.extract(null)).isEmpty();
		assertThat(Wikilinks.extract("")).isEmpty();
		assertThat(Wikilinks.extract("no links here")).isEmpty();
	}

	@Test
	void ignoresEmptyBrackets() {
		assertThat(Wikilinks.extract("[[]] and [[   ]]")).isEmpty();
	}
}
