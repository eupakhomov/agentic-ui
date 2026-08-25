package de.pamir.claude.ui.config;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class SettingsRepository {

	private final JdbcClient jdbc;

	public SettingsRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<String> get(String key) {
		return jdbc.sql("SELECT value FROM app_setting WHERE key = ?").params(key).query(String.class).optional();
	}

	public void set(String key, String value) {
		jdbc.sql("""
						INSERT INTO app_setting (key, value, updated_at) VALUES (?, ?, now())
						ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
						""")
				.params(key, value)
				.update();
	}
}
