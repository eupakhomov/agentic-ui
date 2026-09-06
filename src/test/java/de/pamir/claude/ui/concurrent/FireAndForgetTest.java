package de.pamir.claude.ui.concurrent;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class FireAndForgetTest {

	private final Logger log = LoggerFactory.getLogger(FireAndForgetTest.class);

	@Test
	void runsTaskOnItsOwnThread() throws InterruptedException {
		var latch = new CountDownLatch(1);
		var ranOnOtherThread = new AtomicBoolean(false);
		Thread caller = Thread.currentThread();
		FireAndForget.run("test-task", log, "should not fire", () -> {
			ranOnOtherThread.set(Thread.currentThread() != caller);
			latch.countDown();
		});
		assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(ranOnOtherThread).isTrue();
	}

	@Test
	void swallowsRuntimeExceptionInsteadOfPropagating() throws InterruptedException {
		var latch = new CountDownLatch(1);
		FireAndForget.run("test-task-throws", log, "expected failure", () -> {
			latch.countDown();
			throw new IllegalStateException("boom");
		});
		// no assertion possible on propagation (it's a detached thread) — this just proves
		// the task ran and the JVM/test process doesn't die from the uncaught exception.
		assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
	}
}
