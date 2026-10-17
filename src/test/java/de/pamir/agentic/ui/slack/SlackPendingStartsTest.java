package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.git.ServiceCatalog.Service;
import de.pamir.agentic.ui.slack.SlackCommandHandler.AwaitedPrompt;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SlackPendingStartsTest {

	/** A clock that only moves when told to. */
	private static final class TestClock extends Clock {
		final AtomicLong now = new AtomicLong(1_000_000);

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(now.get());
		}

		@Override
		public long millis() {
			return now.get();
		}
	}

	private final TestClock clock = new TestClock();
	private final SlackPendingStarts pending = new SlackPendingStarts(clock);

	private static AwaitedPrompt awaited(String user, String branch) {
		return new AwaitedPrompt(user, new Service("api", "/eco/api", "/eco/api", false), branch, null);
	}

	@Test
	void theRequesterGetsTheirOpenQuestionBackExactlyOnce() {
		pending.put("C1", "100.1", awaited("U1", "feat/x"));
		assertThat(pending.take("C1", "100.1", "U1")).map(AwaitedPrompt::branch).contains("feat/x");
		assertThat(pending.take("C1", "100.1", "U1")).isEmpty();
	}

	@Test
	void anotherUserCannotAnswerAndLeavesTheQuestionForTheAsker() {
		pending.put("C1", "100.1", awaited("U1", "feat/x"));
		assertThat(pending.take("C1", "100.1", "U2")).isEmpty();
		assertThat(pending.take("C1", "100.1", "U1")).isPresent();
	}

	@Test
	void aDifferentThreadOrChannelIsNotTheSameQuestion() {
		pending.put("C1", "100.1", awaited("U1", "feat/x"));
		assertThat(pending.take("C1", "100.2", "U1")).isEmpty();
		assertThat(pending.take("C2", "100.1", "U1")).isEmpty();
	}

	@Test
	void aQuestionExpiresAfterFifteenMinutes() {
		pending.put("C1", "100.1", awaited("U1", "feat/x"));
		clock.now.addAndGet(Duration.ofMinutes(14).toMillis());
		assertThat(pending.take("C1", "100.1", "U1")).isPresent();

		pending.put("C1", "100.3", awaited("U1", "feat/y"));
		clock.now.addAndGet(SlackPendingStarts.TTL.toMillis());
		assertThat(pending.take("C1", "100.3", "U1")).isEmpty();
	}

	@Test
	void theStoreIsBoundedAndDropsTheOldestFirst() {
		for (int i = 0; i < 60; i++) {
			pending.put("C1", "t" + i, awaited("U1", "b" + i));
		}
		assertThat(pending.take("C1", "t0", "U1")).isEmpty();
		assertThat(pending.take("C1", "t59", "U1")).isPresent();
	}
}
