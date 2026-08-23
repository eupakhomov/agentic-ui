package de.pamir.claude.ui.config;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Shared-token check used by both the REST filter and the WS handshake. */
@Component
public class AuthTokenService {

	private final byte[] token;

	public AuthTokenService(AppProperties props) {
		String configured = props.authToken();
		this.token = configured == null || configured.isBlank()
				? null
				: configured.getBytes(StandardCharsets.UTF_8);
	}

	/** With no token configured, auth is off (startup guard restricts this to loopback). */
	public boolean required() {
		return token != null;
	}

	public boolean matches(String candidate) {
		if (token == null) {
			return true;
		}
		if (candidate == null) {
			return false;
		}
		return MessageDigest.isEqual(token, candidate.getBytes(StandardCharsets.UTF_8));
	}
}
