package de.pamir.claude.ui.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The system-turn plumbing (sessionService/props/settings) is untouched by parse()/
 * parseTickets()/sanitizeBranch(), so a null-dependency instance is a real unit, not a
 * mock — see docs/plan/phase-9-production-hardening.md T1.
 */
class TicketImportServiceTest {

	private final TicketImportService svc = new TicketImportService(null, null, null, new JsonMapper());

	@Test
	void parsesAValidJsonResponse() {
		String raw = "{\"branchName\":\"ENG-123-fix-login\",\"prompt\":\"Fix the login bug\","
				+ "\"recommendedModel\":\"sonnet\",\"ticketRef\":\"eng-123\"}";

		var result = svc.parse(raw);

		assertThat(result.branchName()).isEqualTo("ENG-123-fix-login");
		assertThat(result.prompt()).isEqualTo("Fix the login bug");
		assertThat(result.recommendedModel()).isEqualTo("sonnet");
		assertThat(result.ticketRef()).isEqualTo("ENG-123");
	}

	@Test
	void stripsMarkdownCodeFencesBeforeParsing() {
		String raw = "```json\n{\"branchName\":\"a\",\"prompt\":\"b\"}\n```";

		assertThat(svc.parse(raw).branchName()).isEqualTo("a");
	}

	@Test
	void dropsAnInvalidRecommendedModelRatherThanPassingItThrough() {
		String raw = "{\"branchName\":\"a\",\"prompt\":\"b\",\"recommendedModel\":\"gpt-5\"}";

		assertThat(svc.parse(raw).recommendedModel()).isNull();
	}

	@Test
	void throwsWhenBranchNameOrPromptIsMissing() {
		assertThrows(IllegalStateException.class, () -> svc.parse("{\"branchName\":\"\",\"prompt\":\"\"}"));
	}

	@Test
	void throwsOnUnparsableJson() {
		assertThrows(IllegalStateException.class, () -> svc.parse("not json at all"));
	}

	@Test
	void parseTicketsExtractsAPlainArrayAndSkipsIncompleteEntries() {
		String raw = "[{\"ref\":\"ENG-1\",\"title\":\"A\",\"status\":\"Todo\"},{\"ref\":\"\",\"title\":\"skip me\"}]";

		var tickets = svc.parseTickets(raw);

		assertThat(tickets).hasSize(1);
		assertThat(tickets.get(0).ref()).isEqualTo("ENG-1");
		assertThat(tickets.get(0).title()).isEqualTo("A");
	}

	@Test
	void parseTicketsUnwrapsATicketsWrapperObject() {
		String raw = "{\"tickets\":[{\"ref\":\"ENG-2\",\"title\":\"B\",\"status\":\"Done\"}]}";

		var tickets = svc.parseTickets(raw);

		assertThat(tickets).hasSize(1);
		assertThat(tickets.get(0).ref()).isEqualTo("ENG-2");
	}

	@Test
	void sanitizeBranchStripsUnsafeCharsCollapsesDashesAndLowercasesNothingExtra() {
		assertThat(TicketImportService.sanitizeBranch("  eng-123 fix!!login   bug  "))
				.isEqualTo("eng-123-fix-login-bug");
	}

	@Test
	void sanitizeBranchTruncatesTo60Chars() {
		assertThat(TicketImportService.sanitizeBranch("a".repeat(100))).hasSize(60);
	}
}
