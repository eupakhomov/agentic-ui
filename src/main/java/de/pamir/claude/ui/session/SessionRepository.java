package de.pamir.claude.ui.session;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SessionRepository {

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final RowMapper<SessionEntity> rowMapper;

	public SessionRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.rowMapper = this::mapRow;
	}

	public void insert(SessionEntity s) {
		jdbc.sql("""
						INSERT INTO session (id, name, provider, provider_config, repo_path, ecosystem_path,
							context_dirs, branch, base_branch, worktree_path, model, permission_mode,
							allowed_tools, disallowed_tools, mcp_config, env_vars, skill_sources, agent_sources,
							instructions, thinking, effort, max_turns, fallback_model, cost_budget_usd,
							kickoff_prompt, state)
						VALUES (?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
							?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
						""")
				.params(s.id(), s.name(), s.provider(), json(s.providerConfig()), s.repoPath(), s.ecosystemPath(),
						json(s.contextDirs()), s.branch(), s.baseBranch(), s.worktreePath(), s.model(),
						s.permissionMode(), json(s.allowedTools()), json(s.disallowedTools()), json(s.mcpConfig()),
						json(s.envVars()), json(s.skillSources()), json(s.agentSources()), s.instructions(),
						s.thinking(), s.effort(), s.maxTurns(), s.fallbackModel(), s.costBudgetUsd(),
						s.kickoffPrompt(), s.state().name())
				.update();
	}

	public Optional<SessionEntity> find(UUID id) {
		return jdbc.sql("SELECT * FROM session WHERE id = ?").params(id).query(rowMapper).optional();
	}

	public SessionEntity get(UUID id) {
		return find(id).orElseThrow(() -> new NoSuchElementException("session " + id + " not found"));
	}

	public List<SessionEntity> findAll() {
		return jdbc.sql("SELECT * FROM session ORDER BY created_at DESC").query(rowMapper).list();
	}

	public List<SessionEntity> findByStates(List<SessionState> states) {
		return jdbc.sql("SELECT * FROM session WHERE state = ANY(?::text[]) ORDER BY created_at")
				.params((Object) states.stream().map(Enum::name).toArray(String[]::new))
				.query(rowMapper).list();
	}

	public void updateState(UUID id, SessionState state) {
		jdbc.sql("UPDATE session SET state = ?, updated_at = now() WHERE id = ?")
				.params(state.name(), id).update();
	}

	public void updateProviderSessionId(UUID id, String providerSessionId) {
		jdbc.sql("UPDATE session SET provider_session_id = ?, updated_at = now() WHERE id = ?")
				.params(providerSessionId, id).update();
	}

	public void updateCapabilities(UUID id, JsonNode capabilities) {
		jdbc.sql("UPDATE session SET capabilities = ?::jsonb, updated_at = now() WHERE id = ?")
				.params(json(capabilities), id).update();
	}

	public void updatePermissionMode(UUID id, String mode) {
		jdbc.sql("UPDATE session SET permission_mode = ?, updated_at = now() WHERE id = ?")
				.params(mode, id).update();
	}

	public long countByStates(List<SessionState> states) {
		return jdbc.sql("SELECT count(*) FROM session WHERE state = ANY(?::text[])")
				.params((Object) states.stream().map(Enum::name).toArray(String[]::new))
				.query(Long.class).single();
	}

	// --- queue ---

	public record QueuedMessage(long pos, String text) {
	}

	public void enqueue(UUID sessionId, String text) {
		jdbc.sql("INSERT INTO session_queue (session_id, text) VALUES (?, ?)").params(sessionId, text).update();
	}

	public List<QueuedMessage> queued(UUID sessionId) {
		return jdbc.sql("SELECT pos, text FROM session_queue WHERE session_id = ? ORDER BY pos")
				.params(sessionId)
				.query((rs, i) -> new QueuedMessage(rs.getLong("pos"), rs.getString("text"))).list();
	}

	public Optional<QueuedMessage> pollQueue(UUID sessionId) {
		var head = jdbc.sql("SELECT pos, text FROM session_queue WHERE session_id = ? ORDER BY pos LIMIT 1")
				.params(sessionId)
				.query((rs, i) -> new QueuedMessage(rs.getLong("pos"), rs.getString("text"))).optional();
		head.ifPresent(m -> jdbc.sql("DELETE FROM session_queue WHERE session_id = ? AND pos = ?")
				.params(sessionId, m.pos()).update());
		return head;
	}

	public boolean deleteQueued(UUID sessionId, long pos) {
		return jdbc.sql("DELETE FROM session_queue WHERE session_id = ? AND pos = ?")
				.params(sessionId, pos).update() > 0;
	}

	// --- mapping helpers ---

	private String json(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return mapper.writeValueAsString(value);
		} catch (JacksonException e) {
			throw new IllegalStateException("JSON serialization failed", e);
		}
	}

	private JsonNode readNode(String value) {
		if (value == null) {
			return null;
		}
		try {
			return mapper.readTree(value);
		} catch (JacksonException e) {
			throw new IllegalStateException("bad JSONB payload", e);
		}
	}

	private List<String> readStringList(String value) {
		if (value == null) {
			return List.of();
		}
		try {
			return mapper.readValue(value, new TypeReference<>() {
			});
		} catch (JacksonException e) {
			throw new IllegalStateException("bad JSONB list", e);
		}
	}

	private SessionEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new SessionEntity(
				rs.getObject("id", UUID.class),
				rs.getString("name"),
				rs.getString("provider"),
				readNode(rs.getString("provider_config")),
				rs.getString("repo_path"),
				rs.getString("ecosystem_path"),
				readStringList(rs.getString("context_dirs")),
				rs.getString("branch"),
				rs.getString("base_branch"),
				rs.getString("worktree_path"),
				rs.getString("provider_session_id"),
				readNode(rs.getString("capabilities")),
				rs.getString("model"),
				rs.getString("permission_mode"),
				readStringList(rs.getString("allowed_tools")),
				readStringList(rs.getString("disallowed_tools")),
				readNode(rs.getString("mcp_config")),
				readNode(rs.getString("env_vars")),
				readNode(rs.getString("skill_sources")),
				readNode(rs.getString("agent_sources")),
				rs.getString("instructions"),
				rs.getString("thinking"),
				rs.getString("effort"),
				(Integer) rs.getObject("max_turns"),
				rs.getString("fallback_model"),
				rs.getBigDecimal("cost_budget_usd"),
				rs.getString("kickoff_prompt"),
				SessionState.valueOf(rs.getString("state")),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant());
	}
}
