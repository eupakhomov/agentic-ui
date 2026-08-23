package de.pamir.claude.ui.session;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TemplateRepository {

	public record TemplateEntity(UUID id, String name, String description, JsonNode config,
								 Instant createdAt, Instant updatedAt) {
	}

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final RowMapper<TemplateEntity> rowMapper;

	public TemplateRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.rowMapper = this::mapRow;
	}

	public TemplateEntity insert(String name, String description, JsonNode config) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO session_template (id, name, description, config) VALUES (?, ?, ?, ?::jsonb)")
				.params(id, name, description, write(config)).update();
		return get(id);
	}

	public TemplateEntity update(UUID id, String name, String description, JsonNode config) {
		int updated = jdbc.sql("""
						UPDATE session_template SET name = ?, description = ?, config = ?::jsonb, updated_at = now()
						WHERE id = ?""")
				.params(name, description, write(config), id).update();
		if (updated == 0) {
			throw new NoSuchElementException("template " + id + " not found");
		}
		return get(id);
	}

	public TemplateEntity get(UUID id) {
		return find(id).orElseThrow(() -> new NoSuchElementException("template " + id + " not found"));
	}

	public Optional<TemplateEntity> find(UUID id) {
		return jdbc.sql("SELECT * FROM session_template WHERE id = ?").params(id).query(rowMapper).optional();
	}

	public List<TemplateEntity> findAll() {
		return jdbc.sql("SELECT * FROM session_template ORDER BY name").query(rowMapper).list();
	}

	public boolean delete(UUID id) {
		return jdbc.sql("DELETE FROM session_template WHERE id = ?").params(id).update() > 0;
	}

	private String write(JsonNode config) {
		try {
			return mapper.writeValueAsString(config);
		} catch (JacksonException e) {
			throw new IllegalStateException("JSON serialization failed", e);
		}
	}

	private TemplateEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
		try {
			return new TemplateEntity(
					rs.getObject("id", UUID.class),
					rs.getString("name"),
					rs.getString("description"),
					mapper.readTree(rs.getString("config")),
					rs.getTimestamp("created_at").toInstant(),
					rs.getTimestamp("updated_at").toInstant());
		} catch (JacksonException e) {
			throw new SQLException("bad template config JSONB", e);
		}
	}
}
