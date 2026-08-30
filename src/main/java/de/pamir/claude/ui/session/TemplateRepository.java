package de.pamir.claude.ui.session;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TemplateRepository {

	/** A template's live reference to a library asset, resolved for display (name/location/status). */
	public record TemplateAsset(UUID id, String kind, String name, String location, String status) {
	}

	public record TemplateEntity(UUID id, String name, String description, JsonNode config,
								 List<TemplateAsset> assets, Instant createdAt, Instant updatedAt) {
	}

	private static final String SELECT = """
			SELECT t.*, coalesce(json_agg(json_build_object(
					'id', a.id, 'kind', ta.kind, 'name', a.name, 'location', a.location, 'status', a.status
				) ORDER BY a.name) FILTER (WHERE a.id IS NOT NULL), '[]') AS assets
			FROM session_template t
			LEFT JOIN template_asset ta ON ta.template_id = t.id
			LEFT JOIN library_asset a ON a.id = ta.asset_id""";

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final RowMapper<TemplateEntity> rowMapper;

	public TemplateRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.rowMapper = this::mapRow;
	}

	@Transactional
	public TemplateEntity insert(String name, String description, JsonNode config,
								 List<UUID> skillAssetIds, List<UUID> agentAssetIds) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO session_template (id, name, description, config) VALUES (?, ?, ?, ?::jsonb)")
				.params(id, name, description, write(config)).update();
		linkAssets(id, skillAssetIds, agentAssetIds);
		return get(id);
	}

	@Transactional
	public TemplateEntity update(UUID id, String name, String description, JsonNode config,
								 List<UUID> skillAssetIds, List<UUID> agentAssetIds) {
		int updated = jdbc.sql("""
						UPDATE session_template SET name = ?, description = ?, config = ?::jsonb, updated_at = now()
						WHERE id = ?""")
				.params(name, description, write(config), id).update();
		if (updated == 0) {
			throw new NoSuchElementException("template " + id + " not found");
		}
		linkAssets(id, skillAssetIds, agentAssetIds);
		return get(id);
	}

	private void linkAssets(UUID templateId, List<UUID> skillAssetIds, List<UUID> agentAssetIds) {
		jdbc.sql("DELETE FROM template_asset WHERE template_id = ?").params(templateId).update();
		for (UUID assetId : skillAssetIds != null ? skillAssetIds : List.<UUID>of()) {
			jdbc.sql("INSERT INTO template_asset (template_id, asset_id, kind) VALUES (?, ?, 'skill')")
					.params(templateId, assetId).update();
		}
		for (UUID assetId : agentAssetIds != null ? agentAssetIds : List.<UUID>of()) {
			jdbc.sql("INSERT INTO template_asset (template_id, asset_id, kind) VALUES (?, ?, 'agent')")
					.params(templateId, assetId).update();
		}
	}

	public TemplateEntity get(UUID id) {
		return find(id).orElseThrow(() -> new NoSuchElementException("template " + id + " not found"));
	}

	public Optional<TemplateEntity> find(UUID id) {
		return jdbc.sql(SELECT + " WHERE t.id = ? GROUP BY t.id").params(id).query(rowMapper).optional();
	}

	public List<TemplateEntity> findAll() {
		return jdbc.sql(SELECT + " GROUP BY t.id ORDER BY t.name").query(rowMapper).list();
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
			List<TemplateAsset> assets = mapper.readTree(rs.getString("assets"))
					.valueStream()
					.map(n -> new TemplateAsset(
							UUID.fromString(n.get("id").asText()),
							n.get("kind").asText(),
							n.get("name").asText(),
							n.get("location").asText(),
							n.get("status").asText()))
					.toList();
			return new TemplateEntity(
					rs.getObject("id", UUID.class),
					rs.getString("name"),
					rs.getString("description"),
					mapper.readTree(rs.getString("config")),
					assets,
					rs.getTimestamp("created_at").toInstant(),
					rs.getTimestamp("updated_at").toInstant());
		} catch (JacksonException e) {
			throw new SQLException("bad template config JSONB", e);
		}
	}
}
