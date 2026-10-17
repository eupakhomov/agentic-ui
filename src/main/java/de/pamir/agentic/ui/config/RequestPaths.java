package de.pamir.agentic.ui.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;

import java.util.Locale;

/**
 * The path the auth filters decide on. {@code getRequestURI()} is the raw request line, but Spring
 * routes on the decoded, normalized path, so {@code /%61pi/x}, {@code //api/x}, {@code /./api/x},
 * {@code /y/../api/x} and {@code /api;a=b/x} would all slip past a {@code startsWith("/api/")} on the
 * raw string while still reaching the controller. This decodes, drops {@code ;} params, collapses
 * slashes and resolves {@code .}/{@code ..} first, and lower-cases so a case trick can't help either.
 */
final class RequestPaths {

	private RequestPaths() {
	}

	static String normalized(HttpServletRequest request) {
		String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
		return StringUtils.cleanPath(path).toLowerCase(Locale.ROOT);
	}

	/** {@code /actuator/health} and its sub-paths (liveness/readiness probes) — the only actuator paths left open. */
	static boolean isHealth(String path) {
		return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
	}

	static boolean isApi(String path) {
		return path.startsWith("/api/") || path.equals("/api");
	}

	static boolean isActuator(String path) {
		return path.startsWith("/actuator/") || path.equals("/actuator");
	}

	static boolean isWebSocket(String path) {
		return path.startsWith("/ws/") || path.equals("/ws");
	}
}
