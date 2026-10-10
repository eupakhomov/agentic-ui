package de.pamir.agentic.ui;

import de.pamir.agentic.ui.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class Application {

	private static final Logger log = LoggerFactory.getLogger(Application.class);

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	CommandLineRunner logConfig(AppProperties props) {
		return args -> {
			log.info(
					"agentic-ui config: repoPath={}, worktreeRoot={}, skillsRoot={}, maxSessions={}, authToken={}, providers={}",
					props.repoPath(), props.worktreeRoot(), props.skillsRoot(),
					props.maxSessions(),
					props.authToken() == null || props.authToken().isBlank() ? "<unset>" : "****",
					props.providers() == null ? "{}" : props.providers().keySet());
			// dist/ is gitignored and `mvn package` doesn't build the sidecars, so a fresh clone
			// boots fine but every session would crash — say so at startup, not at first session.
			if (props.providers() != null) {
				props.providers().forEach((id, provider) -> provider.command().stream()
						.filter(part -> part.endsWith(".js")).findFirst().ifPresent(script -> {
							if (!java.nio.file.Files.isRegularFile(java.nio.file.Path.of(script))) {
								log.warn("provider '{}': sidecar not built ({} missing) — run `npm install && npm run build` "
										+ "in its package directory (docs/DEPLOY.md §5); sessions on it will fail",
										id, java.nio.file.Path.of(script).toAbsolutePath().normalize());
							}
						}));
			}
			// Printed in full (not masked, unlike the summary line above) so the dashboard login
			// token is discoverable from the log alone — no separate `cat /tmp/agentic-ui.token`
			// step needed. Same exposure as that token file already sitting in plaintext on disk;
			// this just adds the rolling log file (logs/agentic-ui.log, 14 days kept) to where it lives.
			if (props.authToken() != null && !props.authToken().isBlank()) {
				log.info("agentic-ui auth token (dashboard login): {}", props.authToken());
			}
		};
	}
}
