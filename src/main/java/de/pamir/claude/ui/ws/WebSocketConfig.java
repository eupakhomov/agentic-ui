package de.pamir.claude.ui.ws;

import de.pamir.claude.ui.config.AuthTokenService;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final SessionWebSocketHandler handler;
	private final AuthTokenService auth;

	public WebSocketConfig(SessionWebSocketHandler handler, AuthTokenService auth) {
		this.handler = handler;
		this.auth = auth;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(handler, "/ws/sessions/*")
				.addInterceptors(authInterceptor())
				.setAllowedOrigins("*");
	}

	/**
	 * Token travels as a Sec-WebSocket-Protocol entry: the client requests
	 * ["claude-ui.v1", "bearer.<token>"]; the server validates the bearer entry and
	 * echoes claude-ui.v1 (via SubProtocolCapable) per RFC 6455.
	 */
	private HandshakeInterceptor authInterceptor() {
		return new HandshakeInterceptor() {
			@Override
			public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
										   WebSocketHandler wsHandler, Map<String, Object> attributes) {
				if (!auth.required()) {
					return true;
				}
				List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
				if (protocols != null) {
					for (String headerValue : protocols) {
						for (String entry : headerValue.split(",")) {
							String candidate = entry.strip();
							if (candidate.startsWith("bearer.") && auth.matches(candidate.substring("bearer.".length()))) {
								return true;
							}
						}
					}
				}
				response.setStatusCode(HttpStatus.UNAUTHORIZED);
				return false;
			}

			@Override
			public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
									   WebSocketHandler wsHandler, Exception exception) {
			}
		};
	}
}
