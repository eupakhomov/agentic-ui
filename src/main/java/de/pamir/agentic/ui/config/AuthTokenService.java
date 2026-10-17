package de.pamir.agentic.ui.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared-token check used by both the REST filter and the WS handshake. Whether the token is
 * enforced is decided <b>once, at construction</b>: the {@code auth.token-required} setting is
 * read then, so a change made in the Settings dialog applies on the next backend start (phase 17 B).
 */
@Component
public class AuthTokenService {

	private final byte[] token;
	private final boolean required;

	@Autowired
	public AuthTokenService(AppProperties props, SettingsService settings) {
		this(props.authToken(), settings.current().authTokenRequired());
	}

	/** Test seam: the already-resolved token and setting. */
	AuthTokenService(String configuredToken, boolean tokenRequiredSetting) {
		this.token = configuredToken == null || configuredToken.isBlank()
				? null
				: configuredToken.getBytes(StandardCharsets.UTF_8);
		// "required" with no token to check against can only mean off
		this.required = this.token != null && tokenRequiredSetting;
	}

	/** Effective state: true only when a token is configured AND the setting asked for it at startup. */
	public boolean required() {
		return required;
	}

	public boolean tokenConfigured() {
		return token != null;
	}

	public boolean matches(String candidate) {
		if (!required) {
			return true;
		}
		if (candidate == null) {
			return false;
		}
		return MessageDigest.isEqual(token, candidate.getBytes(StandardCharsets.UTF_8));
	}
}
