package de.pamir.agentic.ui.integration;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.SettingsRepository;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.session.SystemTurnClient;
import de.pamir.agentic.ui.session.SystemTurnLane;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The system-turn plumbing (systemTurnClient/props/settings) is untouched by parse()/
 * parseTickets()/sanitizeBranch(), so a null-dependency instance is a real unit, not a
 * mock — see docs/plan/phase-9-production-hardening.md T1. JSON parsing itself (fence-
 * stripping, malformed-input handling) now lives in SystemTurnClient (G1) and is tested
 * there; these tests exercise field validation on an already-parsed JsonNode.
 *
 * <p>The caching tests below (Step A1, docs/plan/phase-12-linear-cache-serena-context.md) use a
 * {@link FakeSystemTurnClient} (a real subclass overriding json(), same "fake over mock" style as
 * {@code SettingsServiceTest}'s anonymous {@link SettingsRepository}) and a {@link MutableClock}
 * to control TTL expiry deterministically.
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

	// ------------------------------------------------------------------ Step A1: caching

	private static final String TICKETS_JSON = "[{\"ref\":\"ENG-1\",\"title\":\"A\",\"status\":\"Todo\"}]";
	private static final String IMPORT_JSON = "{\"branchName\":\"eng-123-fix\",\"prompt\":\"fix it\",\"ticketRef\":\"ENG-123\"}";

	private MutableClock clock;
	private FakeSystemTurnClient turnClient;
	private TicketImportService cachingSvc;

	private void setUp() {
		clock = new MutableClock();
		turnClient = new FakeSystemTurnClient();
		Map<String, String> store = new HashMap<>();
		SettingsRepository repo = new SettingsRepository(null) {
			@Override
			public Optional<String> get(String key) {
				return Optional.ofNullable(store.get(key));
			}

			@Override
			public void set(String key, String value) {
				store.put(key, value);
			}

			@Override
			public Map<String, String> all() {
				return new HashMap<>(store);
			}
		};
		SettingsService settings = new SettingsService(repo, null, null);
		AppProperties props = new AppProperties(null, null, null, null, 0, null, "test-linear-key", null, null,
				0, 0, 0, null);
		cachingSvc = new TicketImportService(turnClient, props, settings, clock);
	}

	@Test
	void listMyTicketsServesFromCacheWithinTtl() {
		setUp();
		turnClient.enqueue(TICKETS_JSON);

		var first = cachingSvc.listMyTickets(false);
		var second = cachingSvc.listMyTickets(false);

		assertThat(turnClient.calls.get()).isEqualTo(1);
		assertThat(first.cached()).isFalse();
		assertThat(second.cached()).isTrue();
		assertThat(second.tickets()).isEqualTo(first.tickets());
		assertThat(second.fetchedAt()).isEqualTo(first.fetchedAt());
	}

	@Test
	void listMyTicketsRefetchesAfterTtlExpires() {
		setUp();
		turnClient.enqueue(TICKETS_JSON);
		turnClient.enqueue(TICKETS_JSON);

		cachingSvc.listMyTickets(false);
		clock.advance(TicketCache.TTL.plusSeconds(1));
		var second = cachingSvc.listMyTickets(false);

		assertThat(turnClient.calls.get()).isEqualTo(2);
		assertThat(second.cached()).isFalse();
	}

	@Test
	void refreshTrueBypassesTheCache() {
		setUp();
		turnClient.enqueue(TICKETS_JSON);
		turnClient.enqueue(TICKETS_JSON);

		cachingSvc.listMyTickets(false);
		var refreshed = cachingSvc.listMyTickets(true);

		assertThat(turnClient.calls.get()).isEqualTo(2);
		assertThat(refreshed.cached()).isFalse();
	}

	@Test
	void concurrentListFetchesShareOneSystemTurn() throws InterruptedException {
		setUp();
		CountDownLatch fetchStarted = new CountDownLatch(1);
		CountDownLatch releaseFetch = new CountDownLatch(1);
		turnClient.enqueueBlocking(TICKETS_JSON, fetchStarted, releaseFetch);

		BlockingQueue<TicketImportService.TicketList> results = new ArrayBlockingQueue<>(2);
		Runnable call = () -> results.add(cachingSvc.listMyTickets(false));
		Thread t1 = new Thread(call);
		Thread t2 = new Thread(call);
		t1.start();
		assertThat(fetchStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		t2.start();
		Thread.sleep(50); // give t2 a chance to join the in-flight fetch before it's released
		releaseFetch.countDown();
		t1.join(5000);
		t2.join(5000);

		assertThat(turnClient.calls.get()).isEqualTo(1);
		assertThat(results).hasSize(2);
		assertThat(results.poll().tickets()).isEqualTo(results.poll().tickets());
	}

	@Test
	void importTicketCachesByUppercasedRef() {
		setUp();
		turnClient.enqueue(IMPORT_JSON);

		var first = cachingSvc.importTicket("eng-123");
		var second = cachingSvc.importTicket("ENG-123");

		assertThat(turnClient.calls.get()).isEqualTo(1);
		assertThat(second).isEqualTo(first);
	}

	@Test
	void importTicketCachesUnderBothTheInputAndTheCanonicalRef() {
		setUp();
		turnClient.enqueue(IMPORT_JSON);

		cachingSvc.importTicket("https://linear.app/team/issue/eng-123/some-title");
		var second = cachingSvc.importTicket("ENG-123");

		assertThat(turnClient.calls.get()).isEqualTo(1);
		assertThat(second.ticketRef()).isEqualTo("ENG-123");
	}

	/** Real subclass (not a mock) overriding just the one method the service calls. */
	private static final class FakeSystemTurnClient extends SystemTurnClient {
		final AtomicInteger calls = new AtomicInteger();
		final BlockingQueue<java.util.function.Supplier<JsonNode>> responses = new java.util.concurrent.LinkedBlockingQueue<>();
		private final ObjectMapper mapper = new JsonMapper();

		FakeSystemTurnClient() {
			super(null, new JsonMapper());
		}

		void enqueue(String json) {
			responses.add(() -> mapper.readTree(json));
		}

		void enqueueBlocking(String json, CountDownLatch started, CountDownLatch release) {
			responses.add(() -> {
				started.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return mapper.readTree(json);
			});
		}

		@Override
		public JsonNode json(String prompt, SystemTurnLane lane, Duration timeout) {
			calls.incrementAndGet();
			var next = responses.poll();
			if (next == null) {
				throw new IllegalStateException("no fake response queued");
			}
			return next.get();
		}
	}

	/** A {@link Clock} whose {@link #instant()} only moves when {@link #advance} is called. */
	private static final class MutableClock extends Clock {
		private Instant now = Instant.parse("2024-01-01T00:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

		void advance(Duration d) {
			now = now.plus(d);
		}
	}
}
