package de.pamir.agentic.ui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * What a client must look like when the bearer token is off (phase 17 B). Env / yaml only — these
 * are security boundaries, deliberately not editable from the dashboard.
 *
 * @param trustedCidrs   client addresses accepted when the token is off (default: loopback only)
 * @param allowedHosts   accepted {@code Host} header names, port-agnostic (DNS-rebinding guard)
 * @param allowedOrigins extra accepted {@code Origin}s (scheme://host[:port]); an Origin whose host
 *                       is in {@code allowedHosts} is accepted without being listed here
 */
@ConfigurationProperties(prefix = "artus.auth")
public record AuthProperties(
		@DefaultValue({"127.0.0.0/8", "::1/128"}) List<String> trustedCidrs,
		@DefaultValue({"localhost", "127.0.0.1", "[::1]"}) List<String> allowedHosts,
		@DefaultValue List<String> allowedOrigins) {
}
