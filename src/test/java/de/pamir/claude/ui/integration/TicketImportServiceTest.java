package de.pamir.claude.ui.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The system-turn plumbing (systemTurnClient/props/settings) is untouched by parse()/
 * parseTickets()/sanitizeBranch(), so a null-dependency instance is a real unit, not a
 * mock — see docs/plan/phase-9-production-hardening.md T1. JSON parsing itself (fence-
 * stripping, malformed-input handling) now lives in SystemTurnClient (G1) and is tested
 * there; these tests exercise field validation on an already-parsed JsonNode.
 */
class TicketImportServiceTest {

	private final ObjectMapper mapper = new JsonMapper();
	private final TicketImportService svc = new TicketImportService(null, null, null);

	private JsonNode node(String json) {
		return mapper.readTree(json);
	}

	private static final Set<String> VALID_MODELS = Set.of("sonnet", "opus", "haiku");

	@Test
	void parsesAValidJsonResponse() {
		var result = TicketImportService.parse(node("{\"branchName\":\"ENG-123-fix-login\",\"prompt\":\"Fix the login bug\","
				+ "\"recommendedModel\":\"sonnet\",\"ticketRef\":\"eng-123\"}"), VALID_MODELS);

		assertThat(result.branchName()).isEqualTo("ENG-123-fix-login");
		assertThat(result.prompt()).isEqualTo("Fix the login bug");
		assertThat(result.recommendedModel()).isEqualTo("sonnet");
		assertThat(result.ticketRef()).isEqualTo("ENG-123");
	}

	@Test
	void dropsAnInvalidRecommendedModelRatherThanPassingItThrough() {
		var result = TicketImportService.parse(
				node("{\"branchName\":\"a\",\"prompt\":\"b\",\"recommendedModel\":\"gpt-5\"}"), VALID_MODELS);

		assertThat(result.recommendedModel()).isNull();
	}

	@Test
	void throwsWhenBranchNameOrPromptIsMissing() {
		assertThrows(IllegalStateException.class,
				() -> TicketImportService.parse(node("{\"branchName\":\"\",\"prompt\":\"\"}"), VALID_MODELS));
	}

	@Test
	void parseTicketsExtractsAPlainArrayAndSkipsIncompleteEntries() {
		var tickets = svc.parseTickets(node(
				"[{\"ref\":\"ENG-1\",\"title\":\"A\",\"status\":\"Todo\"},{\"ref\":\"\",\"title\":\"skip me\"}]"));

		assertThat(tickets).hasSize(1);
		assertThat(tickets.get(0).ref()).isEqualTo("ENG-1");
		assertThat(tickets.get(0).title()).isEqualTo("A");
	}

	@Test
	void parseTicketsUnwrapsATicketsWrapperObject() {
		var tickets = svc.parseTickets(node("{\"tickets\":[{\"ref\":\"ENG-2\",\"title\":\"B\",\"status\":\"Done\"}]}"));

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
