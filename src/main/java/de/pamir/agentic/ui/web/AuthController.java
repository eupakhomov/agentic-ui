package de.pamir.agentic.ui.web;

import de.pamir.agentic.ui.config.AuthTokenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Effective auth state, for the Settings dialog ("applies on next restart": the stored setting can
 * differ from what the running process enforces). Open even when the token is required; says
 * nothing beyond two booleans.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	public record AuthStatus(boolean required, boolean tokenConfigured) {
	}

	private final AuthTokenService auth;

	public AuthController(AuthTokenService auth) {
		this.auth = auth;
	}

	@GetMapping("/status")
	public AuthStatus status() {
		return new AuthStatus(auth.required(), auth.tokenConfigured());
	}
}
