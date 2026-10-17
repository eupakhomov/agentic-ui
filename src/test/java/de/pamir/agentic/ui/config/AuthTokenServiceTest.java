package de.pamir.agentic.ui.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTokenServiceTest {

	@Test
	void tokenConfiguredAndRequiredEnforcesIt() {
		AuthTokenService auth = new AuthTokenService("secret", true);
		assertThat(auth.required()).isTrue();
		assertThat(auth.matches("secret")).isTrue();
		assertThat(auth.matches("wrong")).isFalse();
		assertThat(auth.matches(null)).isFalse();
	}

	@Test
	void tokenConfiguredButSettingOffIsNotEnforced() {
		AuthTokenService auth = new AuthTokenService("secret", false);
		assertThat(auth.required()).isFalse();
		assertThat(auth.tokenConfigured()).isTrue();
		assertThat(auth.matches(null)).isTrue();
	}

	@Test
	void settingOnWithoutATokenIsEffectivelyOff() {
		AuthTokenService auth = new AuthTokenService("", true);
		assertThat(auth.required()).isFalse();
		assertThat(auth.tokenConfigured()).isFalse();
	}

	@Test
	void noTokenAndSettingOffIsOff() {
		assertThat(new AuthTokenService(null, false).required()).isFalse();
	}
}
