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
public class AssetSourceRepository {

	public record SourceEntity(UUID id, String type, String ref, boolean syncEnabled, Instant lastSyncedAt,
								String lastSyncStatus, String lastSyncError, Instant createdAt, Instant updatedAt) {
	}

	public record DiscoveryEntity(UUID sourceId, String sourcePath, String kind, Instant firstSeenAt) {
	}

	private final JdbcClient jdbc;
	private final RowMapper<SourceEntity> rowMapper;
	private final RowMapper<DiscoveryEntity> discoveryMapper;

	public AssetSourceRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.rowMapper = this::mapRow;
		this.discoveryMapper = (rs, n) -> new DiscoveryEntity(
				rs.getObject("source_id", UUID.class), rs.getString("source_path"),
				rs.getString("kind"), rs.getTimestamp("first_seen_at").toInstant());
	}

	/** Insert-or-update by unique ref; sync_enabled reflects the latest import's choice. */
	public SourceEntity upsert(String type, String ref, boolean syncEnabled) {
		jdbc.sql("""
						INSERT INTO asset_source (id, type, ref, sync_enabled) VALUES (?, ?, ?, ?)
						ON CONFLICT (ref) DO UPDATE SET sync_enabled = EXCLUDED.sync_enabled, updated_at = now()""")
				.params(UUID.randomUUID(), type, ref, syncEnabled).update();
		return findByRef(ref).orElseThrow();
	}

	public SourceEntity get(UUID id) {
		return jdbc.sql("SELECT * FROM asset_source WHERE id = ?").params(id).query(rowMapper).optional()
				.orElseThrow(() -> new NoSuchElementException("source " + id + " not found"));
	}

	public Optional<SourceEntity> findByRef(String ref) {
		return jdbc.sql("SELECT * FROM asset_source WHERE ref = ?").params(ref).query(rowMapper).optional();
	}

	public List<SourceEntity> findAll() {
		return jdbc.sql("SELECT * FROM asset_source ORDER BY ref").query(rowMapper).list();
	}

	public List<SourceEntity> findSyncDue(Instant cutoff) {
		return jdbc.sql("""
						SELECT * FROM asset_source
						WHERE sync_enabled AND (last_synced_at IS NULL OR last_synced_at <= ?) ORDER BY ref""")
				.params(java.sql.Timestamp.from(cutoff)).query(rowMapper).list();
	}

	public void setSyncEnabled(UUID id, boolean enabled) {
		int updated = jdbc.sql("UPDATE asset_source SET sync_enabled = ?, updated_at = now() WHERE id = ?")
				.params(enabled, id).update();
		if (updated == 0) {
			throw new NoSuchElementException("source " + id + " not found");
		}
	}

	public void recordSync(UUID id, String status, String error) {
		jdbc.sql("""
						UPDATE asset_source SET last_synced_at = now(), last_sync_status = ?, last_sync_error = ?,
							updated_at = now() WHERE id = ?""")
				.params(status, error, id).update();
	}

	public boolean delete(UUID id) {
		return jdbc.sql("DELETE FROM asset_source WHERE id = ?").params(id).update() > 0;
	}

	// --- discoveries (new upstream files awaiting review) ---

	/** Keeps first_seen_at and the dismissed flag on re-discovery of a known path. */
	public void upsertDiscovery(UUID sourceId, String sourcePath, String kind) {
		jdbc.sql("""
						INSERT INTO source_discovery (source_id, source_path, kind) VALUES (?, ?, ?)
						ON CONFLICT (source_id, source_path) DO NOTHING""")
				.params(sourceId, sourcePath, kind).update();
	}

	public List<DiscoveryEntity> findUndismissedDiscoveries(UUID sourceId) {
		return jdbc.sql("""
						SELECT * FROM source_discovery WHERE source_id = ? AND NOT dismissed
						ORDER BY first_seen_at, source_path""")
				.params(sourceId).query(discoveryMapper).list();
	}

	public void dismissDiscoveries(UUID sourceId, List<String> sourcePaths) {
		if (sourcePaths == null) {
			jdbc.sql("UPDATE source_discovery SET dismissed = true WHERE source_id = ?").params(sourceId).update();
			return;
		}
		for (String path : sourcePaths) {
			jdbc.sql("UPDATE source_discovery SET dismissed = true WHERE source_id = ? AND source_path = ?")
					.params(sourceId, path).update();
		}
	}

	public void deleteDiscovery(UUID sourceId, String sourcePath) {
		jdbc.sql("DELETE FROM source_discovery WHERE source_id = ? AND source_path = ?")
				.params(sourceId, sourcePath).update();
	}

	/** Drops discovery rows whose upstream file vanished before ever being reviewed. */
	public void pruneDiscoveriesNotIn(UUID sourceId, List<String> currentPaths) {
		if (currentPaths.isEmpty()) {
			jdbc.sql("DELETE FROM source_discovery WHERE source_id = ?").params(sourceId).update();
			return;
		}
		String placeholders = String.join(", ", java.util.Collections.nCopies(currentPaths.size(), "?"));
		List<Object> params = new java.util.ArrayList<>();
		params.add(sourceId);
		params.addAll(currentPaths);
		jdbc.sql("DELETE FROM source_discovery WHERE source_id = ? AND source_path NOT IN (" + placeholders + ")")
				.params(params).update();
	}

	private SourceEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
		var syncedAt = rs.getTimestamp("last_synced_at");
		return new SourceEntity(
				rs.getObject("id", UUID.class),
				rs.getString("type"),
				rs.getString("ref"),
				rs.getBoolean("sync_enabled"),
				syncedAt == null ? null : syncedAt.toInstant(),
				rs.getString("last_sync_status"),
				rs.getString("last_sync_error"),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant());
	}
}
