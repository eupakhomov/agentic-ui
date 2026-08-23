package de.pamir.claude.ui.journal;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Append-only per-session event journal with backend-assigned monotonic seq.
 * stream_delta events are buffered and batch-inserted (they dominate volume);
 * any other event type flushes the buffer first so insertion order equals seq order.
 */
@Component
public class EventJournal {

	public record JournalEvent(long seq, Instant ts, String type, JsonNode payload) {
	}

	private static final int FLUSH_THRESHOLD = 50;
	private static final long FLUSH_INTERVAL_MS = 250;

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final int payloadCapBytes;
	private final Map<UUID, PerSession> sessions = new ConcurrentHashMap<>();
	private final ScheduledExecutorService flusher =
			Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("journal-flusher").factory());

	public EventJournal(JdbcClient jdbc, ObjectMapper mapper, de.pamir.claude.ui.config.AppProperties props) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.payloadCapBytes = props.journalPayloadCapBytes();
		flusher.scheduleWithFixedDelay(this::flushAll, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	/** Assigns the next seq, journals the event (possibly buffered), returns the envelope. */
	public JournalEvent append(UUID sessionId, String type, JsonNode payload) {
		PerSession ps = sessions.computeIfAbsent(sessionId, this::load);
		synchronized (ps) {
			JournalEvent event = new JournalEvent(++ps.lastSeq, Instant.now(), type, cap(payload));
			ps.buffer.add(event);
			if (!"stream_delta".equals(type) || ps.buffer.size() >= FLUSH_THRESHOLD) {
				flush(sessionId, ps);
			}
			return event;
		}
	}

	/** Persist everything buffered for this session (called before any replay read). */
	public void flush(UUID sessionId) {
		PerSession ps = sessions.get(sessionId);
		if (ps != null) {
			synchronized (ps) {
				flush(sessionId, ps);
			}
		}
	}

	public long lastSeq(UUID sessionId) {
		PerSession ps = sessions.computeIfAbsent(sessionId, this::load);
		synchronized (ps) {
			return ps.lastSeq;
		}
	}

	public List<JournalEvent> readAfter(UUID sessionId, long afterSeq) {
		flush(sessionId);
		return jdbc.sql("SELECT seq, ts, type, payload FROM session_event WHERE session_id = ? AND seq > ? ORDER BY seq")
				.params(sessionId, afterSeq)
				.query((rs, i) -> new JournalEvent(
						rs.getLong("seq"),
						rs.getTimestamp("ts").toInstant(),
						rs.getString("type"),
						readNode(rs.getString("payload"))))
				.list();
	}

	/**
	 * Coalescing: once a turn is complete, its stream_delta rows are redundant with the
	 * journaled assistant_message events — drop them so long sessions stay bounded.
	 */
	public int deleteDeltasBefore(UUID sessionId, long beforeSeq) {
		flush(sessionId);
		return jdbc.sql("DELETE FROM session_event WHERE session_id = ? AND type = 'stream_delta' AND seq < ?")
				.params(sessionId, beforeSeq).update();
	}

	/** Defense in depth: no single journal row grows beyond the cap. */
	private JsonNode cap(JsonNode payload) {
		String serialized = write(payload);
		if (serialized.length() <= payloadCapBytes) {
			return payload;
		}
		var trimmed = mapper.createObjectNode();
		trimmed.put("truncated", true);
		trimmed.put("originalBytes", serialized.length());
		trimmed.put("preview", serialized.substring(0, 4096));
		return trimmed;
	}

	/** Sum of turn_complete costUsd for the session (0 if none). */
	public java.math.BigDecimal costToDate(UUID sessionId) {
		flush(sessionId);
		return jdbc.sql("""
						SELECT coalesce(sum((payload->>'costUsd')::numeric), 0)
						FROM session_event WHERE session_id = ? AND type = 'turn_complete'""")
				.params(sessionId).query(java.math.BigDecimal.class).single();
	}

	@PreDestroy
	void shutdown() {
		flusher.shutdown();
		flushAll();
	}

	private void flushAll() {
		for (var entry : sessions.entrySet()) {
			synchronized (entry.getValue()) {
				flush(entry.getKey(), entry.getValue());
			}
		}
	}

	private void flush(UUID sessionId, PerSession ps) {
		if (ps.buffer.isEmpty()) {
			return;
		}
		// row-by-row inside one loop; volume is bounded by FLUSH_THRESHOLD
		for (JournalEvent e : ps.buffer) {
			jdbc.sql("INSERT INTO session_event (session_id, seq, ts, type, payload) VALUES (?, ?, ?, ?, ?::jsonb)")
					.params(sessionId, e.seq(), Timestamp.from(e.ts()), e.type(), write(e.payload()))
					.update();
		}
		ps.buffer.clear();
	}

	private PerSession load(UUID sessionId) {
		Long max = jdbc.sql("SELECT max(seq) FROM session_event WHERE session_id = ?")
				.params(sessionId).query(Long.class).optional().orElse(null);
		PerSession ps = new PerSession();
		ps.lastSeq = max == null ? 0 : max;
		return ps;
	}

	private String write(JsonNode node) {
		try {
			return mapper.writeValueAsString(node);
		} catch (JacksonException e) {
			throw new IllegalStateException("JSON serialization failed", e);
		}
	}

	private JsonNode readNode(String value) {
		try {
			return mapper.readTree(value);
		} catch (JacksonException e) {
			throw new IllegalStateException("bad JSONB payload", e);
		}
	}

	private static final class PerSession {
		long lastSeq;
		final List<JournalEvent> buffer = new ArrayList<>();
	}
}
