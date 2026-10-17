package de.pamir.agentic.ui.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalClientFilterTest {

	private static final AuthProperties DEFAULTS = new AuthProperties(
			List.of("127.0.0.0/8", "::1/128"), List.of("localhost", "127.0.0.1", "[::1]"), List.of());

	private static LocalClientFilter filter(AuthProperties props) {
		return new LocalClientFilter(new AuthTokenService("", false), props);
	}

	private final LocalClientFilter f = filter(DEFAULTS);

	@Test
	void loopbackClientWithLocalHostIsAccepted() {
		assertThat(f.check("127.0.0.1", "localhost:8080", null, null)).isNull();
		assertThat(f.check("127.0.0.1", "127.0.0.1:8080", null, "same-origin")).isNull();
		assertThat(f.check("0:0:0:0:0:0:0:1", "[::1]:8080", null, null)).isNull();
	}

	@Test
	void ipv4MappedLoopbackCountsAsLoopback() {
		assertThat(f.check("::ffff:127.0.0.1", "localhost", null, null)).isNull();
	}

	@Test
	void untrustedClientAddressIsRefusedEvenWithAGoodHost() {
		assertThat(f.check("192.168.1.20", "localhost:8080", null, null)).contains("client address");
	}

	@Test
	void foreignHostIsRefusedAgainstDnsRebinding() {
		assertThat(f.check("127.0.0.1", "evil.example:8080", null, null)).contains("Host");
		assertThat(f.check("127.0.0.1", null, null, null)).contains("Host");
	}

	@Test
	void portDoesNotMatterForHost() {
		assertThat(f.check("127.0.0.1", "localhost:5173", null, null)).isNull();
		assertThat(f.check("127.0.0.1", "localhost", null, null)).isNull();
	}

	@Test
	void originOnAnAllowedHostIsAcceptedOnAnyPort() {
		assertThat(f.check("127.0.0.1", "localhost:8080", "http://localhost:5173", null)).isNull();
		assertThat(f.check("127.0.0.1", "localhost:8080", "http://[::1]:8080", null)).isNull();
	}

	@Test
	void foreignOriginIsRefused() {
		assertThat(f.check("127.0.0.1", "localhost:8080", "https://evil.example", null)).contains("Origin");
		assertThat(f.check("127.0.0.1", "localhost:8080", "null", null)).contains("Origin");
	}

	@Test
	void crossSiteFetchIsRefusedEvenWithGoodHeaders() {
		assertThat(f.check("127.0.0.1", "localhost:8080", null, "cross-site")).contains("cross-site");
	}

	@Test
	void listedOriginAndHostForAReverseProxyAreAccepted() {
		LocalClientFilter proxied = filter(new AuthProperties(List.of("127.0.0.0/8"),
				List.of("localhost", "dash.tailnet.ts.net"), List.of("https://other.example")));
		assertThat(proxied.check("127.0.0.1", "dash.tailnet.ts.net", "https://dash.tailnet.ts.net", null)).isNull();
		assertThat(proxied.check("127.0.0.1", "localhost", "https://other.example", null)).isNull();
		assertThat(proxied.check("127.0.0.1", "localhost", "https://third.example", null)).contains("Origin");
	}

	@Test
	void cidrMatchingHandlesPartialByteMasks() {
		LocalClientFilter lan = filter(new AuthProperties(List.of("172.20.0.0/12"), List.of("localhost"), List.of()));
		assertThat(lan.check("172.31.255.1", "localhost", null, null)).isNull();
		assertThat(lan.check("172.32.0.1", "localhost", null, null)).contains("client address");
	}

	@Test
	void badCidrFailsFastAtStartup() {
		assertThatThrownBy(() -> filter(new AuthProperties(List.of("10.0.0.0/40"), List.of("localhost"), List.of())))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void hostOnlyKeepsIpv6Brackets() {
		assertThat(LocalClientFilter.hostOnly("[::1]:8080")).isEqualTo("[::1]");
		assertThat(LocalClientFilter.hostOnly("LocalHost:8080")).isEqualTo("localhost");
	}

	@Test
	void filterAnswers403ProblemJsonAndDoesNotCallTheChain() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/sessions");
		req.setRemoteAddr("192.168.1.20");
		req.addHeader("Host", "localhost:8080");
		MockHttpServletResponse res = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		f.doFilter(req, res, chain);
		assertThat(res.getStatus()).isEqualTo(403);
		assertThat(res.getContentType()).isEqualTo("application/problem+json");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void wsHandshakeAndActuatorAreCoveredButHealthAndStaticAreNot() throws Exception {
		assertThat(shouldSkip("/ws/sessions/abc")).isFalse();
		assertThat(shouldSkip("/actuator/metrics")).isFalse();
		assertThat(shouldSkip("/actuator/health")).isTrue();
		assertThat(shouldSkip("/index.html")).isTrue();
	}

	@Test
	void doesNothingWhileTheTokenIsEnforced() throws Exception {
		LocalClientFilter tokenOn = new LocalClientFilter(new AuthTokenService("secret", true), DEFAULTS);
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/sessions");
		req.setRemoteAddr("203.0.113.9");
		MockFilterChain chain = new MockFilterChain();
		tokenOn.doFilter(req, new MockHttpServletResponse(), chain);
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void encodedAndDotSegmentTricksDoNotEscapeTheFilter() {
		for (String sneaky : new String[] {"/%61pi/sessions", "/%41PI/sessions", "//api/sessions", "/./api/sessions",
				"/x/../api/sessions", "/api;a=b/sessions", "/API/sessions", "/actuator/health/../metrics",
				"/actuator/%6detrics", "/%77s/sessions/x", "/actuator/health/%2e%2e/metrics"}) {
			assertThat(shouldSkip(sneaky)).as(sneaky).isFalse();
		}
		assertThat(shouldSkip("/actuator/health/liveness")).isTrue();
		assertThat(shouldSkip("/actuator/%68ealth")).isTrue(); // decodes to /actuator/health
	}

	@Test
	void tokenFilterAppliesTheSameNormalization() {
		AuthTokenFilter token = new AuthTokenFilter(new AuthTokenService("secret", true));
		for (String sneaky : new String[] {"/%61pi/sessions", "//api/sessions", "/x/../api/sessions",
				"/api;a=b/sessions", "/actuator/health/../metrics"}) {
			assertThat(token.shouldNotFilter(new MockHttpServletRequest("GET", sneaky))).as(sneaky).isFalse();
		}
		assertThat(token.shouldNotFilter(new MockHttpServletRequest("GET", "/api/auth/status"))).isTrue();
		assertThat(token.shouldNotFilter(new MockHttpServletRequest("GET", "/index.html"))).isTrue();
	}

	private boolean shouldSkip(String uri) {
		return f.shouldNotFilter(new MockHttpServletRequest("GET", uri));
	}
}
