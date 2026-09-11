package de.pamir.claude.ui.discovery;

import de.pamir.claude.ui.library.PgVector;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD + hybrid (dense + sparse) search over per-service discovery profiles (see
 * docs/plan/phase-8-service-discovery.md). Unlike memory's {@code memory_doc}, there is no
 * filesystem source of truth — this table IS the record, generated and cached, never hand-edited.
 */
@Repository
public class ServiceProfileRepository {

	/**
	 * {@code servicePath} is the identity (UNIQUE) — a folder inside a git repo, keyed the same way
	 * {@code session.service_path} is; {@code repoPath} is the git root for that service, nullable
	 * until a caller supplies it (Step 5 of docs/plan/phase-11-monorepo.md). Polyrepo has
	 * {@code repoPath == servicePath}.
	 */
	public record ServiceProfile(UUID id, String servicePath, String repoPath, String name, String description,
								  List<String> tags, String lastCommitSha, Instant discoveredAt, UUID sessionId,
								  Instant createdAt, Instant updatedAt) {
	}

	public record SearchHit(ServiceProfile profile, double score) {
	}

	private static final String SELECT = "SELECT id, service_path, repo_path, name, description, tags, "
			+ "last_commit_sha, discovered_at, session_id, created_at, updated_at FROM service_profile";
	private static final int ARM_LIMIT = 50;
	private static final double RRF_K = 60.0;

	private final JdbcClient jdbc;
	private final RowMapper<ServiceProfile> rowMapper = this::mapRow;

	public ServiceProfileRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<ServiceProfile> findByServicePath(String servicePath) {
		return jdbc.sql(SELECT + " WHERE service_path = ?").params(servicePath).query(rowMapper).optional();
	}

	public List<ServiceProfile> findAll() {
		return jdbc.sql(SELECT + " ORDER BY name").query(rowMapper).list();
	}

	/** Discovered profiles restricted to a caller-supplied visible set (list_discovered_services). */
	public List<ServiceProfile> findVisible(List<String> servicePaths) {
		if (servicePaths == null || servicePaths.isEmpty()) {
			return List.of();
		}
		return jdbc.sql(SELECT + " WHERE service_path = ANY(?::text[]) ORDER BY name")
				.params(toArrayLiteral(servicePaths)).query(rowMapper).list();
	}

	/** Insert or fully replace a profile's generated content (a new description was actually produced). */
	public ServiceProfile upsert(String servicePath, String repoPath, String name, String description,
								  List<String> tags, String lastCommitSha, UUID sessionId) {
		jdbc.sql("""
						INSERT INTO service_profile (id, service_path, repo_path, name, description, tags,
							last_commit_sha, discovered_at, session_id)
						VALUES (?, ?, ?, ?, ?, ?::text[], ?, now(), ?)
						ON CONFLICT (service_path) DO UPDATE SET
							repo_path = EXCLUDED.repo_path, name = EXCLUDED.name, description = EXCLUDED.description,
							tags = EXCLUDED.tags, last_commit_sha = EXCLUDED.last_commit_sha, discovered_at = now(),
							session_id = EXCLUDED.session_id, updated_at = now()""")
				.params(UUID.randomUUID(), servicePath, repoPath, name, description, toArrayLiteral(tags),
						lastCommitSha, sessionId)
				.update();
		return findByServicePath(servicePath).orElseThrow();
	}

	/** The repo hasn't changed since the last run (decision 4) — just refresh the timestamp, no content touched. */
	public void bumpDiscoveredAt(String servicePath) {
		jdbc.sql("UPDATE service_profile SET discovered_at = now() WHERE service_path = ?")
				.params(servicePath).update();
	}

	public void upsertEmbedding(String servicePath, float[] embedding, String model) {
		jdbc.sql("UPDATE service_profile SET embedding = ?::vector, embedding_model = ? WHERE service_path = ?")
				.params(PgVector.literal(embedding), model, servicePath).update();
	}

	/**
	 * Dense + sparse + trigram hybrid search fused with RRF, restricted to {@code visiblePaths} — the
	 * calling session's own visible ecosystem (mirrors {@code list_services}' scoping, decision 7).
	 * An empty/null {@code visiblePaths} means nothing is visible (never "everything") — callers must
	 * resolve an explicit set first.
	 */
	public List<SearchHit> hybridSearch(String queryText, float[] queryEmbedding, List<String> visiblePaths,
										 int limit) {
		if (visiblePaths == null || visiblePaths.isEmpty()) {
			return List.of();
		}
		String pathFilter = " AND service_path = ANY(?::text[])";
		String pathArray = toArrayLiteral(visiblePaths);

		StringBuilder sql = new StringBuilder("WITH ");
		List<Object> params = new ArrayList<>();
		List<String> arms = new ArrayList<>();
		if (queryEmbedding != null) {
			sql.append("""
							dense AS (
								SELECT id, rnk FROM (
									SELECT id, row_number() OVER (ORDER BY embedding <=> ?::vector) AS rnk
									FROM service_profile WHERE embedding IS NOT NULL""").append(pathFilter).append("""
								) x ORDER BY rnk LIMIT %d
							),
							""".formatted(ARM_LIMIT));
			params.add(PgVector.literal(queryEmbedding));
			params.add(pathArray);
			arms.add("dense");
		}
		sql.append("""
						sparse AS (
							SELECT id, rnk FROM (
								SELECT id, row_number() OVER (
									ORDER BY ts_rank_cd(tsv, websearch_to_tsquery('english', ?)) DESC) AS rnk
								FROM service_profile WHERE tsv @@ websearch_to_tsquery('english', ?)""")
				.append(pathFilter).append("""
						) x ORDER BY rnk LIMIT %d
					),
					""".formatted(ARM_LIMIT));
		params.add(queryText);
		params.add(queryText);
		params.add(pathArray);
		arms.add("sparse");

		sql.append("""
						trgm AS (
							SELECT id, rnk FROM (
								SELECT id, row_number() OVER (ORDER BY similarity(description, ?) DESC) AS rnk
								FROM service_profile WHERE description % ?""")
				.append(pathFilter).append("""
						) x ORDER BY rnk LIMIT %d
					),
					""".formatted(ARM_LIMIT));
		params.add(queryText);
		params.add(queryText);
		params.add(pathArray);
		arms.add("trgm");

		sql.append("fused AS (SELECT id, SUM(1.0 / (").append(RRF_K).append(" + rnk)) AS score FROM (")
				.append(String.join(" UNION ALL ", arms.stream().map(a -> "SELECT * FROM " + a).toList()))
				.append(") u GROUP BY id) ")
				.append("SELECT p.id, p.service_path, p.repo_path, p.name, p.description, p.tags, p.last_commit_sha, ")
				.append("p.discovered_at, p.session_id, p.created_at, p.updated_at, f.score AS score ")
				.append("FROM fused f JOIN service_profile p ON p.id = f.id ORDER BY f.score DESC LIMIT ?");
		params.add(limit);
		return jdbc.sql(sql.toString()).params(params)
				.query((rs, n) -> new SearchHit(mapRow(rs, n), rs.getDouble("score"))).list();
	}

	private static String[] tagList(ResultSet rs) throws SQLException {
		var arr = rs.getArray("tags");
		return arr == null ? new String[0] : (String[]) arr.getArray();
	}

	private ServiceProfile mapRow(ResultSet rs, int rowNum) throws SQLException {
		Object sessionIdObj = rs.getObject("session_id");
		return new ServiceProfile(
				rs.getObject("id", UUID.class),
				rs.getString("service_path"),
				rs.getString("repo_path"),
				rs.getString("name"),
				rs.getString("description"),
				List.of(tagList(rs)),
				rs.getString("last_commit_sha"),
				rs.getTimestamp("discovered_at").toInstant(),
				sessionIdObj == null ? null : (UUID) sessionIdObj,
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant());
	}

	/** Postgres array literal, e.g. {"a","b"} — bound as plain text and cast with ::text[]. */
	private static String toArrayLiteral(List<String> values) {
		if (values == null || values.isEmpty()) {
			return "{}";
		}
		StringBuilder sb = new StringBuilder("{");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
		}
		return sb.append('}').toString();
	}
}
