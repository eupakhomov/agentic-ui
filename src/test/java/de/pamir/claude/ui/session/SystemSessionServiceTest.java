package de.pamir.claude.ui.session;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * extractText moved here from SessionServiceTest along with runSystemTurn's home (see
 * docs/plan/phase-9-production-hardening.md S1); the pending-turn handshake itself
 * (onAssistantMessage/completeTurn/failTurn, the lock/lane behavior) is covered by
 * SessionStateMachineTest's T2 suite, which drives it through a fake SessionRepository/
 * SessionService rather than reaching into private fields here.
 */
class SystemSessionServiceTest {

	private final ObjectMapper mapper = new JsonMapper();

	@Test
	void extractTextJoinsOnlyTextBlocksInOrder() {
		ArrayNode content = mapper.createArrayNode();
		content.add(mapper.createObjectNode().put("type", "text").put("text", "Hello "));
		content.add(mapper.createObjectNode().put("type", "tool_use").put("name", "Bash"));
		content.add(mapper.createObjectNode().put("type", "text").put("text", "World"));

		assertThat(SystemSessionService.extractText(content)).isEqualTo("Hello World");
	}

	@Test
	void extractTextIsEmptyForNullOrNonArrayContent() {
		assertThat(SystemSessionService.extractText(null)).isEmpty();
		assertThat(SystemSessionService.extractText(mapper.createObjectNode())).isEmpty();
	}
}
