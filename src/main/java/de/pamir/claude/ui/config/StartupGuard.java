package de.pamir.claude.ui.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Fail fast rather than run an unauthenticated command-execution service on the LAN:
 * an empty auth token is only allowed when bound to loopback.
 */
@Component
public class StartupGuard implements InitializingBean {

	private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "::1");

	private final AppProperties props;
	private final String bindAddress;

	public StartupGuard(AppProperties props, @Value("${server.address:127.0.0.1}") String bindAddress) {
		this.props = props;
		this.bindAddress = bindAddress;
	}

	@Override
	public void afterPropertiesSet() {
		boolean tokenMissing = props.authToken() == null || props.authToken().isBlank();
		if (tokenMissing && !LOOPBACK.contains(bindAddress)) {
			throw new IllegalStateException(
					"refusing to bind to " + bindAddress + " without an auth token; "
							+ "set claude-ui.auth-token (CLAUDE_UI_TOKEN) or bind to 127.0.0.1");
		}
	}
}
