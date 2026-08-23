package de.pamir.claude.ui.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Bearer-token gate for /api/**. WS handshakes are covered by the handshake interceptor. */
@Component
public class AuthTokenFilter extends OncePerRequestFilter {

	private static final String PREFIX = "Bearer ";

	private final AuthTokenService auth;

	public AuthTokenFilter(AuthTokenService auth) {
		this.auth = auth;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!auth.required()) {
			chain.doFilter(request, response);
			return;
		}
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith(PREFIX) && auth.matches(header.substring(PREFIX.length()))) {
			chain.doFilter(request, response);
			return;
		}
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType("application/problem+json");
		response.getWriter().write("{\"status\":401,\"detail\":\"missing or invalid bearer token\"}");
	}
}
