package de.pamir.claude.ui.library;

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
public class LibraryRepository {

	public record AssetEntity(UUID id, UUID sourceId, String kind, String name, String description,
							   String location, String sourcePath, String contentHash, String status,
							   List<String> tags, Instant createdAt, Instant updatedAt) {
	}

	public record SearchHit(AssetEntity asset, double distance) {
	}

	private static final String SELECT = """
			SELECT a.*, coalesce(array_agg(t.tag ORDER BY t.tag) FILTER (WHERE t.tag IS NOT NULL), '{}') AS tags
			FROM library_asset a LEFT JOIN asset_tag t ON t.asset_id = a.id""";

	private final JdbcClient jdbc;
	private final RowMapper<AssetEntity> rowMapper;

	public LibraryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.rowMapper = this::mapRow;
	}

	public AssetEntity insert(UUID sourceId, String kind, String name, String description, String location,
							   String sourcePath, String contentHash, List<String> tags) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
						INSERT INTO library_asset (id, source_id, kind, name, description, location, source_path, content_hash)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
				.params(id, sourceId, kind, name, description, location, sourcePath, contentHash).update();
		replaceTags(id, tags);
		return get(id);
	}

	public AssetEntity get(UUID id) {
		return find(id).orElseThrow(() -> new NoSuchElementException("asset " + id + " not found"));
	}

	public Optional<AssetEntity> find(UUID id) {
		return jdbc.sql(SELECT + " WHERE a.id = ? GROUP BY a.id").params(id).query(rowMapper).optional();
	}

	public List<AssetEntity> findAll(String kind, String status, String query) {
		StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
		List<Object> params = new java.util.ArrayList<>();
		if (kind != null && !kind.isBlank()) {
			sql.append(" AND a.kind = ?");
			params.add(kind);
		}
		if (status != null && !status.isBlank()) {
			sql.append(" AND a.status = ?");
			params.add(status);
		}
		if (query != null && !query.isBlank()) {
			sql.append(" AND (a.name ILIKE ? OR a.description ILIKE ?"
					+ " OR EXISTS (SELECT 1 FROM asset_tag q WHERE q.asset_id = a.id AND q.tag ILIKE ?))");
			String like = "%" + query.strip() + "%";
			params.add(like);
			params.add(like);
			params.add(like);
		}
		sql.append(" GROUP BY a.id ORDER BY a.name");
		return jdbc.sql(sql.toString()).params(params).query(rowMapper).list();
	}

	public List<AssetEntity> findBySource(UUID sourceId) {
		return jdbc.sql(SELECT + " WHERE a.source_id = ? GROUP BY a.id ORDER BY a.name")
				.params(sourceId).query(rowMapper).list();
	}

	public void updateMeta(UUID id, String name, String description) {
		int updated = jdbc.sql("UPDATE library_asset SET name = ?, description = ?, updated_at = now() WHERE id = ?")
				.params(name, description, id).update();
		if (updated == 0) {
			throw new NoSuchElementException("asset " + id + " not found");
		}
	}

	public void updateStatus(UUID id, String status) {
		int updated = jdbc.sql("UPDATE library_asset SET status = ?, updated_at = now() WHERE id = ?")
				.params(status, id).update();
		if (updated == 0) {
			throw new NoSuchElementException("asset " + id + " not found");
		}
	}

	public void updateHash(UUID id, String contentHash) {
		jdbc.sql("UPDATE library_asset SET content_hash = ?, updated_at = now() WHERE id = ?")
				.params(contentHash, id).update();
	}

	public void replaceTags(UUID id, List<String> tags) {
		jdbc.sql("DELETE FROM asset_tag WHERE asset_id = ?").params(id).update();
		if (tags != null) {
			tags.stream().map(String::strip).filter(t -> !t.isEmpty()).distinct().forEach(tag ->
					jdbc.sql("INSERT INTO asset_tag (asset_id, tag) VALUES (?, ?)").params(id, tag).update());
		}
	}

	public boolean delete(UUID id) {
		return jdbc.sql("DELETE FROM library_asset WHERE id = ?").params(id).update() > 0;
	}

	// --- embeddings ---

	public void upsertEmbedding(UUID assetId, float[] embedding, String model) {
		jdbc.sql("""
						INSERT INTO asset_embedding (asset_id, embedding, model) VALUES (?, ?::vector, ?)
						ON CONFLICT (asset_id) DO UPDATE SET embedding = EXCLUDED.embedding,
							model = EXCLUDED.model, embedded_at = now()""")
				.params(assetId, toVectorLiteral(embedding), model).update();
	}

	public List<SearchHit> searchByEmbedding(float[] query, int limit) {
		String sql = """
				SELECT a.*, coalesce(array_agg(t.tag ORDER BY t.tag) FILTER (WHERE t.tag IS NOT NULL), '{}') AS tags,
					min(e.embedding <=> ?::vector) AS distance
				FROM asset_embedding e
				JOIN library_asset a ON a.id = e.asset_id AND a.status = 'ACTIVE'
				LEFT JOIN asset_tag t ON t.asset_id = a.id
				GROUP BY a.id ORDER BY distance LIMIT ?""";
		return jdbc.sql(sql).params(toVectorLiteral(query), limit)
				.query((rs, n) -> new SearchHit(mapRow(rs, n), rs.getDouble("distance"))).list();
	}

	private static String toVectorLiteral(float[] embedding) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < embedding.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(embedding[i]);
		}
		return sb.append(']').toString();
	}

	private AssetEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
		String[] tags = (String[]) rs.getArray("tags").getArray();
		return new AssetEntity(
				rs.getObject("id", UUID.class),
				rs.getObject("source_id", UUID.class),
				rs.getString("kind"),
				rs.getString("name"),
				rs.getString("description"),
				rs.getString("location"),
				rs.getString("source_path"),
				rs.getString("content_hash"),
				rs.getString("status"),
				List.of(tags),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant());
	}
}
