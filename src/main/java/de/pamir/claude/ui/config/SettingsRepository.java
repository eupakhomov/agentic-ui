package de.pamir.claude.ui.config;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
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

	/** Every persisted setting in one round trip — the table has ~25 rows total, no filter needed. */
	public Map<String, String> all() {
		Map<String, String> out = new HashMap<>();
		for (Map.Entry<String, String> row : jdbc.sql("SELECT key, value FROM app_setting")
				.query((rs, rowNum) -> Map.entry(rs.getString("key"), rs.getString("value")))
				.list()) {
			out.put(row.getKey(), row.getValue());
		}
		return out;
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
