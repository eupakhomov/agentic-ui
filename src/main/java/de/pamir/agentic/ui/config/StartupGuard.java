package de.pamir.agentic.ui.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reports the effective auth posture at startup. Before phase 17 this refused to start with no
 * token on a non-loopback bind; that rule moved to a per-request check ({@link LocalClientFilter}),
 * which enforces "only trusted clients" wherever the socket is bound — the WSL setup has to bind
 * {@code 0.0.0.0}, and a loopback reverse proxy is a loopback client.
 */
@Component
public class StartupGuard implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(StartupGuard.class);

	private final AuthTokenService auth;
	private final AuthProperties authProps;
	private final SettingsService settings;
	private final String bindAddress;

	public StartupGuard(AuthTokenService auth, AuthProperties authProps, SettingsService settings,
						@Value("${server.address:127.0.0.1}") String bindAddress) {
		this.auth = auth;
		this.authProps = authProps;
		this.settings = settings;
		this.bindAddress = bindAddress;
	}

	@Override
	public void afterPropertiesSet() {
		if (auth.required()) {
			log.info("auth: bearer token required");
			return;
		}
		if (settings.current().authTokenRequired() && !auth.tokenConfigured()) {
			log.warn("auth: 'Require access token' is on but no token is configured (artus.auth-token / "
					+ "ARTUS_TOKEN) — running with the token off");
		}
		log.warn("auth: token OFF — only clients in {} are accepted (Host in {}) on {}", authProps.trustedCidrs(),
				authProps.allowedHosts(), bindAddress);
	}
}
