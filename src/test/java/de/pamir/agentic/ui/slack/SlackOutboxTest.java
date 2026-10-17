package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.slack.SlackMessaging.SlackMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackOutboxTest {

	private final FakeSlack slack = new FakeSlack();
	private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
	private SlackOutbox outbox;

	private SlackOutbox outbox(int capacity) {
		outbox = new SlackOutbox(slack, Duration.ZERO, capacity, sleeps::add);
		outbox.start();
		return outbox;
	}

	@AfterEach
	void stop() {
		if (outbox != null) {
			outbox.stop();
		}
		if (slack.gate != null) {
			slack.gate.countDown();
		}
	}

	private static SlackMessage msg(String text) {
		return new SlackMessage(text, null);
	}

	@Test
	void postsGoOutInOrderAndCompleteWithTheMessageTimestamp() throws Exception {
		outbox(50);
		CompletableFuture<String> a = outbox.post("C1", null, false, () -> msg("a"));
		CompletableFuture<String> b = outbox.post("C1", "100.1", false, () -> msg("b"));
		assertThat(a.get(5, TimeUnit.SECONDS)).isEqualTo("100.1");
		assertThat(b.get(5, TimeUnit.SECONDS)).isEqualTo("100.2");
		assertThat(slack.of("post")).extracting(c -> c.message().text()).containsExactly("a", "b");
		assertThat(slack.of("post").get(1).extra()).isEqualTo("100.1");
	}

	@Test
	void aRateLimitedCallWaitsRetryAfterThenSucceeds() throws Exception {
		outbox(50);
		slack.failures.add(new SlackApiException("chat.postMessage", "ratelimited", Duration.ofSeconds(7)));
		String ts = outbox.post("C1", null, false, () -> msg("a")).get(5, TimeUnit.SECONDS);
		assertThat(ts).isNotBlank();
		assertThat(sleeps).contains(Duration.ofSeconds(7));
		assertThat(slack.of("post")).hasSize(1);
	}

	@Test
	void aPermanentRefusalFailsAtOnceWithoutRetrying() {
		outbox(50);
		slack.failures.add(new SlackApiException("chat.postMessage", "not_in_channel"));
		CompletableFuture<String> failed = outbox.post("C1", null, false, () -> msg("a"));
		assertThatThrownBy(() -> failed.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
				.hasRootCauseInstanceOf(SlackApiException.class).hasMessageContaining("not_in_channel");
		assertThat(slack.calls).isEmpty();
	}

	@Test
	void aTransientFailureGivesUpAfterTheMaximumNumberOfAttempts() {
		outbox(50);
		for (int i = 0; i < SlackOutbox.MAX_ATTEMPTS; i++) {
			slack.failures.add(new SlackApiException("chat.postMessage", "transport_error"));
		}
		CompletableFuture<String> failed = outbox.post("C1", null, false, () -> msg("a"));
		assertThatThrownBy(() -> failed.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
		assertThat(sleeps.stream().filter(d -> !d.isZero())).containsExactly(
				Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(6), Duration.ofSeconds(8));
	}

	@Test
	void aMessageThatFailsToRenderDoesNotKillTheWorker() throws Exception {
		outbox(50);
		CompletableFuture<String> bad = outbox.post("C1", null, false, () -> {
			throw new IllegalStateException("render failed");
		});
		assertThatThrownBy(() -> bad.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
		assertThat(outbox.post("C1", null, false, () -> msg("after")).get(5, TimeUnit.SECONDS)).isNotBlank();
	}

	@Test
	void updatesForOneKeyCoalesceAndRenderTheLatestContentAtSendTime() throws Exception {
		outbox(50);
		slack.gate = new CountDownLatch(1);
		CompletableFuture<String> blocker = outbox.post("C1", null, false, () -> msg("blocker"));
		AtomicReference<String> content = new AtomicReference<>("first");
		assertThat(outbox.update("parent:1", "C1", "100.9", () -> msg(content.get()))).isTrue();
		content.set("latest");
		assertThat(outbox.update("parent:1", "C1", "100.9", () -> msg("ignored"))).isFalse();
		slack.gate.countDown();
		blocker.get(5, TimeUnit.SECONDS);
		FakeSlack.await(() -> !slack.of("update").isEmpty());
		assertThat(slack.of("update")).hasSize(1);
		assertThat(slack.of("update").get(0).message().text()).isEqualTo("latest");
	}

	@Test
	void updatesAreSentOnlyAfterQueuedPostsHaveGoneOut() throws Exception {
		outbox(50);
		slack.gate = new CountDownLatch(1);
		CompletableFuture<String> blocker = outbox.post("C1", null, false, () -> msg("blocker"));
		outbox.update("parent:1", "C1", "100.9", () -> msg("update"));
		CompletableFuture<String> later = outbox.post("C1", null, false, () -> msg("later post"));
		slack.gate.countDown();
		blocker.get(5, TimeUnit.SECONDS);
		later.get(5, TimeUnit.SECONDS);
		FakeSlack.await(() -> slack.calls.size() == 3);
		assertThat(slack.calls).extracting(c -> c.kind()).containsExactly("post", "post", "update");
	}

	@Test
	void whenFullTheOldestNonCriticalPostIsDroppedAndCriticalOnesAreKept() throws Exception {
		outbox(2);
		slack.gate = new CountDownLatch(1);
		CompletableFuture<String> inFlight = outbox.post("C1", null, true, () -> msg("in flight"));
		// give the worker a moment to take the first item so it no longer counts against the queue
		Thread.sleep(100);
		CompletableFuture<String> chatter = outbox.post("C1", null, false, () -> msg("chatter"));
		CompletableFuture<String> permission = outbox.post("C1", null, true, () -> msg("permission"));
		CompletableFuture<String> plan = outbox.post("C1", null, true, () -> msg("plan"));
		assertThatThrownBy(() -> chatter.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
				.hasMessageContaining("dropped");
		slack.gate.countDown();
		inFlight.get(5, TimeUnit.SECONDS);
		permission.get(5, TimeUnit.SECONDS);
		plan.get(5, TimeUnit.SECONDS);
		assertThat(slack.of("post")).extracting(c -> c.message().text())
				.containsExactly("in flight", "permission", "plan");
	}

	@Test
	void criticalPostsAreNeverDroppedEvenPastCapacity() throws Exception {
		outbox(1);
		slack.gate = new CountDownLatch(1);
		CompletableFuture<String> first = outbox.post("C1", null, true, () -> msg("one"));
		Thread.sleep(100);
		CompletableFuture<String> second = outbox.post("C1", null, true, () -> msg("two"));
		CompletableFuture<String> third = outbox.post("C1", null, true, () -> msg("three"));
		slack.gate.countDown();
		first.get(5, TimeUnit.SECONDS);
		second.get(5, TimeUnit.SECONDS);
		third.get(5, TimeUnit.SECONDS);
		assertThat(slack.of("post")).hasSize(3);
	}
}
