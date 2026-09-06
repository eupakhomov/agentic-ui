package de.pamir.claude.ui.session;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.journal.EventJournal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link EventJournal} double — overrides every method SessionService's tested paths
 * call, bypassing the real jdbc-backed implementation entirely (whose constructor also starts a
 * scheduled flush task; harmless here since the superclass's own per-session buffer, which that
 * task drains, never receives anything — every append this fake handles is short-circuited
 * before touching it). See docs/plan/phase-9-production-hardening.md T2.
 */
final class FakeEventJournal extends EventJournal {

	private final Map<UUID, Long> seqs = new ConcurrentHashMap<>();
	private final Map<UUID, BigDecimal> costs = new ConcurrentHashMap<>();

	FakeEventJournal(AppProperties props) {
		super(null, null, props);
	}

	void setCostToDate(UUID id, BigDecimal cost) {
		costs.put(id, cost);
	}

	@Override
	public JournalEvent append(UUID sessionId, String type, tools.jackson.databind.JsonNode payload) {
		long seq = seqs.merge(sessionId, 1L, Long::sum);
		return new JournalEvent(seq, java.time.Instant.now(), type, payload);
	}

	@Override
	public long lastSeq(UUID sessionId) {
		return seqs.getOrDefault(sessionId, 0L);
	}

	@Override
	public int deleteDeltasBefore(UUID sessionId, long beforeSeq) {
		return 0;
	}

	@Override
	public BigDecimal costToDate(UUID sessionId) {
		return costs.getOrDefault(sessionId, BigDecimal.ZERO);
	}

	@Override
	public List<JournalEvent> readAfter(UUID sessionId, long afterSeq) {
		return List.of();
	}
}
