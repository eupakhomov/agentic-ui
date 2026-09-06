package de.pamir.claude.ui.concurrent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InFlightGuardTest {

	@Test
	void secondAcquireForSameKeyFailsUntilReleased() {
		var guard = new InFlightGuard<String>();
		assertThat(guard.tryAcquire("a")).isTrue();
		assertThat(guard.tryAcquire("a")).isFalse();
		guard.release("a");
		assertThat(guard.tryAcquire("a")).isTrue();
	}

	@Test
	void differentKeysDoNotContend() {
		var guard = new InFlightGuard<String>();
		assertThat(guard.tryAcquire("a")).isTrue();
		assertThat(guard.tryAcquire("b")).isTrue();
	}
}
