package de.pamir.claude.ui.session;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.journal.JournalPublisher;
import de.pamir.claude.ui.journal.SessionEventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves O1's fix (docs/plan/phase-9-production-hardening.md): {@code enforceSessionLimit} and
 * the following {@code insert} must be atomic, or two concurrent creates can both read a
 * under-limit count and both insert, overshooting {@code maxSessions}. Deterministic (no sleep-
 * and-hope): a fake repository's {@code countByStates} blocks the first caller inside the
 * critical section on a latch, so the second caller's attempt to enter is provably still waiting
 * on the lock (its own {@code countByStates} hasn't been called yet) until the first is released.
 */
class SessionServiceEnforceLimitTest {

	@TempDir
	Path worktreeRoot;

	private static SettingsService fakeSettings() {
		return new SettingsService(null, null, null);
	}

	private SessionEntity liveEntity(UUID id) {
		return SessionEntity.builder().id(id).name(id.toString()).provider("claude")
				.repoPath("/repo").branch("b-" + id).baseBranch("main").worktreePath("/wt/" + id)
				.state(SessionState.IDLE).build();
	}

	@Test
	void secondCreateWaitsForFirstsCountAndInsertToCommitAtomically() throws Exception {
		AppProperties props = new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills",
				"/memory", 1, "authtoken", "", "", "logs", 30, 65536, 1048576, Map.of());
		BlockingSessionRepository sessions = new BlockingSessionRepository();
		FakeEventJournal journal = new FakeEventJournal(props);
		JournalPublisher journalPublisher = new JournalPublisher(journal, new SessionEventBus());
		ObjectMapper mapper = new JsonMapper();
		SessionConfigFactory configFactory = new SessionConfigFactory(props, fakeSettings(), null, mapper, null, 8080, null, null);
		SystemSessionService systemSessionService =
				new SystemSessionService(props, fakeSettings(), sessions, configFactory, journalPublisher, mapper, null);
		SessionService sessionService = new SessionService(props, fakeSettings(), sessions, null, null, null, null,
				journal, journalPublisher, mapper, event -> { }, configFactory, systemSessionService, null, null);

		UUID idA = UUID.randomUUID();
		UUID idB = UUID.randomUUID();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Throwable> resultA = pool.submit(() -> {
				try {
					sessionService.enforceSessionLimitAndInsert(liveEntity(idA));
					return null;
				} catch (Throwable t) {
					return t;
				}
			});
			assertThat(sessions.enteredCount.await(2, TimeUnit.SECONDS))
					.as("thread A should have entered the synchronized section").isTrue();

			Future<Throwable> resultB = pool.submit(() -> {
				try {
					sessionService.enforceSessionLimitAndInsert(liveEntity(idB));
					return null;
				} catch (Throwable t) {
					return t;
				}
			});
			// B must still be blocked waiting for the lock — its own countByStates call hasn't
			// started yet, proving the count+insert critical section is mutually exclusive.
			Thread.sleep(300);
			assertThat(sessions.countCalls.get()).as("B must not have entered countByStates yet").isEqualTo(1);
			assertThat(resultB.isDone()).isFalse();

			sessions.releaseCount.countDown();

			assertThat(resultA.get(2, TimeUnit.SECONDS)).isNull();
			Throwable b = resultB.get(2, TimeUnit.SECONDS);
			assertThat(b).isInstanceOf(IllegalStateException.class);
			assertThat(b.getMessage()).contains("max concurrent sessions reached");
			assertThat(sessions.byId).hasSize(1).containsKey(idA);
		} finally {
			pool.shutdownNow();
		}
	}

	/** Delays its first {@code countByStates} call so a second concurrent caller can be proven
	 * to still be waiting for {@link SessionService#sessionLimitLock} at that point. */
	private static final class BlockingSessionRepository extends SessionRepository {
		final Map<UUID, SessionEntity> byId = new ConcurrentHashMap<>();
		final CountDownLatch enteredCount = new CountDownLatch(1);
		final CountDownLatch releaseCount = new CountDownLatch(1);
		final AtomicInteger countCalls = new AtomicInteger();

		BlockingSessionRepository() {
			super(null, null);
		}

		@Override
		public long countByStates(List<SessionState> states) {
			int call = countCalls.incrementAndGet();
			if (call == 1) {
				enteredCount.countDown();
				try {
					releaseCount.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			return byId.values().stream().filter(e -> states.contains(e.state())).count();
		}

		@Override
		public void insert(SessionEntity s) {
			byId.put(s.id(), s);
		}
	}
}
