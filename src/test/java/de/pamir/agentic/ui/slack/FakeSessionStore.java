package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionRepository;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link SessionRepository} for the Slack tests: lookup by id and by Slack thread. */
final class FakeSessionStore extends SessionRepository {

	final Map<UUID, SessionEntity> byId = new ConcurrentHashMap<>();

	FakeSessionStore() {
		super(null, null);
	}

	void put(SessionEntity e) {
		byId.put(e.id(), e);
	}

	@Override
	public Optional<SessionEntity> find(UUID id) {
		return Optional.ofNullable(byId.get(id));
	}

	@Override
	public SessionEntity get(UUID id) {
		return find(id).orElseThrow(() -> new NoSuchElementException(id.toString()));
	}

	@Override
	public java.util.List<SessionEntity> findByParent(UUID parentId) {
		return byId.values().stream().filter(e -> parentId.equals(e.parentSessionId())).toList();
	}

	@Override
	public Optional<SessionEntity> findBySlackThread(String channel, String threadTs) {
		return byId.values().stream()
				.filter(e -> channel.equals(e.slackChannel()) && threadTs.equals(e.slackThreadTs())).findFirst();
	}

	@Override
	public void attachSlackThread(UUID id, String channel, String threadTs) {
		byId.computeIfPresent(id, (k, e) -> e.toBuilder().slackChannel(channel).slackThreadTs(threadTs).build());
	}
}
