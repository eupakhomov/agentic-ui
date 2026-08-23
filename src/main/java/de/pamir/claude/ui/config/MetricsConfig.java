package de.pamir.claude.ui.config;

import de.pamir.claude.ui.session.SessionRepository;
import de.pamir.claude.ui.session.SessionState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class MetricsConfig {

	public MetricsConfig(MeterRegistry registry, SessionRepository sessions) {
		Gauge.builder("claudeui.sessions.active",
						() -> sessions.countByStates(List.copyOf(SessionState.LIVE)))
				.description("sessions with a live sidecar process")
				.register(registry);
		Gauge.builder("claudeui.sessions.parked",
						() -> sessions.countByStates(List.of(SessionState.PARKED)))
				.description("idle-parked sessions")
				.register(registry);
	}
}
