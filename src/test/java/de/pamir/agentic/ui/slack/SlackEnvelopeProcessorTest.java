package de.pamir.agentic.ui.slack;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SlackEnvelopeProcessorTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final List<String> acks = new ArrayList<>();
	private final List<SlackEvent> events = new ArrayList<>();
	private final AtomicInteger reconnects = new AtomicInteger();

	private final SlackEnvelopeProcessor processor = new SlackEnvelopeProcessor(
			mapper, acks::add, Runnable::run, events::add, reconnects::incrementAndGet);

	private static String message(String envelopeId, String eventId) {
		return """
				{"envelope_id":"%s","type":"events_api","accepts_response_payload":false,
				 "payload":{"event_id":"%s","event":{"type":"message","user":"U1","text":"hi","thread_ts":"1.1"}}}
				""".formatted(envelopeId, eventId);
	}

	private static String click(String envelopeId, String triggerId, String actionTs) {
		return """
				{"envelope_id":"%s","type":"interactive","accepts_response_payload":false,
				 "payload":{"type":"block_actions","trigger_id":"%s","user":{"id":"U1"},
				            "actions":[{"action_id":"allow","value":"p1","action_ts":"%s"}]}}
				""".formatted(envelopeId, triggerId, actionTs);
	}

	@Test
	void everyDeliveryIsAckedWithItsEnvelopeId() {
		processor.onFrame(message("env-1", "Ev1"));
		assertThat(acks).hasSize(1);
		assertThat(mapper.readTree(acks.get(0)).path("envelope_id").asText()).isEqualTo("env-1");
		assertThat(events).hasSize(1);
		assertThat(events.get(0).type()).isEqualTo("events_api");
		assertThat(events.get(0).payload().path("event").path("text").asText()).isEqualTo("hi");
	}

	@Test
	void redeliveredEventIsAckedAgainButHandledOnce() {
		processor.onFrame(message("env-1", "Ev1"));
		// Slack resends an unacked envelope with the same event_id (new envelope after a reconnect)
		processor.onFrame(message("env-1", "Ev1"));
		processor.onFrame(message("env-9", "Ev1"));
		assertThat(acks).hasSize(3);
		assertThat(events).hasSize(1);
	}

	@Test
	void distinctEventsAreEachHandled() {
		processor.onFrame(message("env-1", "Ev1"));
		processor.onFrame(message("env-2", "Ev2"));
		assertThat(events).extracting(SlackEvent::dedupeKey).containsExactly("event:Ev1", "event:Ev2");
	}

	@Test
	void buttonClickDedupesOnTriggerAndActionTimestamp() {
		processor.onFrame(click("env-1", "trig-1", "100.1"));
		processor.onFrame(click("env-2", "trig-1", "100.1"));
		assertThat(events).hasSize(1);
		assertThat(events.get(0).dedupeKey()).isEqualTo("action:trig-1:100.1");
		// the same message's button clicked again later is a new action, not a duplicate
		processor.onFrame(click("env-3", "trig-2", "200.2"));
		assertThat(events).hasSize(2);
	}

	@Test
	void deliveryWithoutAStableIdFallsBackToTheEnvelopeId() {
		String noId = "{\"envelope_id\":\"env-7\",\"type\":\"events_api\",\"payload\":{}}";
		processor.onFrame(noId);
		processor.onFrame(noId);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).dedupeKey()).isEqualTo("envelope:env-7");
	}

	@Test
	void eventsAreHandedOverInArrivalOrder() {
		for (int i = 1; i <= 5; i++) {
			processor.onFrame(message("env-" + i, "Ev" + i));
		}
		assertThat(events).extracting(SlackEvent::dedupeKey)
				.containsExactly("event:Ev1", "event:Ev2", "event:Ev3", "event:Ev4", "event:Ev5");
	}

	@Test
	void helloAndUnknownFramesAreNotAckedOrDispatched() {
		processor.onFrame("{\"type\":\"hello\",\"num_connections\":1}");
		processor.onFrame("{\"envelope_id\":\"x\",\"type\":\"slash_commands\",\"payload\":{}}");
		assertThat(acks).isEmpty();
		assertThat(events).isEmpty();
	}

	@Test
	void garbageAndEmptyEnvelopeIdsAreIgnored() {
		processor.onFrame("not json at all");
		processor.onFrame("{\"type\":\"events_api\",\"payload\":{}}");
		assertThat(acks).isEmpty();
		assertThat(events).isEmpty();
	}

	@Test
	void refreshRequestTriggersReconnectButTheAdvanceWarningDoesNot() {
		processor.onFrame("{\"type\":\"disconnect\",\"reason\":\"warning\"}");
		assertThat(reconnects).hasValue(0);
		processor.onFrame("{\"type\":\"disconnect\",\"reason\":\"refresh_requested\"}");
		assertThat(reconnects).hasValue(1);
	}

	@Test
	void aFailingListenerNeitherBreaksTheProcessorNorSkipsLaterEvents() {
		List<SlackEvent> seen = new ArrayList<>();
		SlackEnvelopeProcessor p = new SlackEnvelopeProcessor(mapper, acks::add, Runnable::run, e -> {
			seen.add(e);
			if (e.dedupeKey().equals("event:Ev1")) {
				throw new IllegalStateException("boom");
			}
		}, () -> { });
		p.onFrame(message("env-1", "Ev1"));
		p.onFrame(message("env-2", "Ev2"));
		assertThat(seen).hasSize(2);
		assertThat(acks).hasSize(2);
	}

	@Test
	void dedupeSetEvictsTheOldestKeyPastCapacity() {
		SlackEnvelopeProcessor.RecentKeys keys = new SlackEnvelopeProcessor.RecentKeys(2);
		assertThat(keys.firstTime("a")).isTrue();
		assertThat(keys.firstTime("b")).isTrue();
		assertThat(keys.firstTime("c")).isTrue();
		assertThat(keys.firstTime("c")).isFalse();
		assertThat(keys.firstTime("a")).isTrue();
	}

	@Test
	void propertiesNeverPrintTheTokens() {
		SlackProperties props = new SlackProperties(true, "xoxb-secret", "xapp-secret", "C1", List.of("U1"), false, "", false);
		assertThat(props.toString()).doesNotContain("secret").contains("<set>");
	}
}
