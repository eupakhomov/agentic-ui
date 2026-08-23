package de.pamir.claude.ui;

import de.pamir.claude.ui.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
@org.springframework.scheduling.annotation.EnableScheduling
public class Application {

	private static final Logger log = LoggerFactory.getLogger(Application.class);

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	CommandLineRunner logConfig(AppProperties props) {
		return args -> log.info(
				"claude-ui config: repoPath={}, worktreeRoot={}, ecosystemRoot={}, skillsRoot={}, maxSessions={}, authToken={}, providers={}",
				props.repoPath(), props.worktreeRoot(), props.ecosystemRoot(), props.skillsRoot(),
				props.maxSessions(),
				props.authToken() == null || props.authToken().isBlank() ? "<unset>" : "****",
				props.providers() == null ? "{}" : props.providers().keySet());
	}
}
