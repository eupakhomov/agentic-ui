package de.pamir.claude.ui.library;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The system-turn plumbing (systemTurnClient/scanner) is untouched by parse(), so a
 * null-dependency instance is a real unit — see docs/plan/phase-9-production-hardening.md T1.
 */
class LibraryAiServiceTest {

	private final JsonMapper mapper = new JsonMapper();
	private final LibraryAiService svc = new LibraryAiService(null, null);

	@Test
	void parsesEntriesMatchingARequestedPathAndLowercasesTags() {
		var node = mapper.readTree(
				"[{\"path\":\"a.md\",\"name\":\"A\",\"description\":\"desc\",\"tags\":[\" Foo\",\"BAR\"]}]");

		var results = svc.parse(node, List.of("a.md"));

		assertThat(results).hasSize(1);
		assertThat(results.get(0).name()).isEqualTo("A");
		assertThat(results.get(0).tags()).containsExactly("foo", "bar");
	}

	@Test
	void skipsEntriesForPathsThatWerentRequested() {
		var node = mapper.readTree("[{\"path\":\"unrequested.md\",\"name\":\"A\"}]");

		assertThrows(IllegalStateException.class, () -> svc.parse(node, List.of("a.md")));
	}

	@Test
	void throwsWhenNoEntriesSurviveFiltering() {
		var node = mapper.readTree("[]");

		assertThrows(IllegalStateException.class, () -> svc.parse(node, List.of("a.md")));
	}
}
