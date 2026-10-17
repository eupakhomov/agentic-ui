package de.pamir.agentic.ui.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * The gate that replaces the token when it is off (phase 17 B). This service executes commands, so
 * "no token" must still mean "only the local user":
 * <ul>
 *   <li>the client address must be in {@code agentic-ui.auth.trusted-cidrs} (loopback by default);</li>
 *   <li>the {@code Host} header must be an allowed name (port-agnostic) — blocks DNS rebinding;</li>
 *   <li>a browser {@code Origin}, when present, must belong to an allowed host or be listed — blocks
 *       cross-site requests and WebSocket hijacking from any page the user visits;</li>
 *   <li>{@code Sec-Fetch-Site: cross-site} is refused outright.</li>
 * </ul>
 * Does nothing while the token is enforced (the token is then the gate). Covers REST, the
 * WebSocket handshake (a plain HTTP request to this filter) and non-health actuator endpoints.
 */
@Component
public class LocalClientFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(LocalClientFilter.class);

	private final AuthTokenService auth;
	private final List<Cidr> trusted;
	private final List<String> allowedHosts;
	private final List<String> allowedOrigins;

	public LocalClientFilter(AuthTokenService auth, AuthProperties props) {
		this.auth = auth;
		this.trusted = props.trustedCidrs().stream().map(Cidr::parse).toList();
		this.allowedHosts = props.allowedHosts().stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
		this.allowedOrigins = props.allowedOrigins().stream().map(o -> o.strip().toLowerCase(Locale.ROOT)).toList();
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (auth.required()) {
			return true;
		}
		String path = RequestPaths.normalized(request);
		if (RequestPaths.isActuator(path)) {
			return RequestPaths.isHealth(path);
		}
		return !(RequestPaths.isApi(path) || RequestPaths.isWebSocket(path));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String denied = check(request.getRemoteAddr(), request.getHeader("Host"), request.getHeader("Origin"),
				request.getHeader("Sec-Fetch-Site"));
		if (denied == null) {
			chain.doFilter(request, response);
			return;
		}
		log.warn("auth off: refused {} {} from {} — {}", request.getMethod(), request.getRequestURI(),
				request.getRemoteAddr(), denied);
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType("application/problem+json");
		response.getWriter().write("{\"status\":403,\"detail\":\"auth disabled; only trusted local clients are accepted ("
				+ denied + ")\"}");
	}

	/** Null when the request is acceptable, else a short reason. Package-private for tests. */
	String check(String remoteAddr, String host, String origin, String secFetchSite) {
		if (!isTrusted(remoteAddr)) {
			return "client address not trusted";
		}
		if (host == null || !allowedHosts.contains(hostOnly(host))) {
			return "Host not allowed";
		}
		if ("cross-site".equalsIgnoreCase(secFetchSite)) {
			return "cross-site request";
		}
		if (origin != null && !originAllowed(origin)) {
			return "Origin not allowed";
		}
		return null;
	}

	private boolean isTrusted(String remoteAddr) {
		if (remoteAddr == null) {
			return false;
		}
		try {
			InetAddress addr = InetAddress.getByName(remoteAddr);
			return trusted.stream().anyMatch(c -> c.contains(addr));
		} catch (IOException e) {
			return false;
		}
	}

	/** Host header without port; keeps the brackets of an IPv6 literal ({@code [::1]:8080} → {@code [::1]}). */
	static String hostOnly(String host) {
		String h = host.strip().toLowerCase(Locale.ROOT);
		if (h.startsWith("[")) {
			int end = h.indexOf(']');
			return end < 0 ? h : h.substring(0, end + 1);
		}
		int colon = h.lastIndexOf(':');
		return colon < 0 ? h : h.substring(0, colon);
	}

	private boolean originAllowed(String origin) {
		String o = origin.strip().toLowerCase(Locale.ROOT);
		if (allowedOrigins.contains(o)) {
			return true;
		}
		try {
			String host = URI.create(o).getHost();
			if (host == null) {
				return false; // "null" (sandboxed iframe, file://) and garbage
			}
			String bare = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
			return allowedHosts.contains(bare);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** An address block, IPv4 and IPv6, matched on raw bytes (an IPv4-mapped IPv6 address parses as IPv4). */
	record Cidr(byte[] network, int prefix) {

		static Cidr parse(String spec) {
			String s = spec.strip();
			int slash = s.indexOf('/');
			try {
				InetAddress addr = InetAddress.getByName(slash < 0 ? s : s.substring(0, slash));
				int bits = addr.getAddress().length * 8;
				int prefix = slash < 0 ? bits : Integer.parseInt(s.substring(slash + 1));
				if (prefix < 0 || prefix > bits) {
					throw new IllegalArgumentException("bad prefix length in trusted CIDR: " + spec);
				}
				return new Cidr(addr.getAddress(), prefix);
			} catch (IOException | NumberFormatException e) {
				throw new IllegalArgumentException("invalid trusted CIDR: " + spec, e);
			}
		}

		boolean contains(InetAddress addr) {
			byte[] a = addr.getAddress();
			if (a.length != network.length) {
				return false;
			}
			int full = prefix / 8;
			for (int i = 0; i < full; i++) {
				if (a[i] != network[i]) {
					return false;
				}
			}
			int rem = prefix % 8;
			if (rem == 0) {
				return true;
			}
			int mask = 0xFF << (8 - rem) & 0xFF;
			return (a[full] & mask) == (network[full] & mask);
		}
	}
}
