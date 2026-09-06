package de.pamir.claude.ui.concurrent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Skip if one is already running for this key" guard, shared by {@code ReflectionService}
 * (keyed by session id) and {@code ServiceDiscoveryService} (keyed by repo path) — see
 * docs/plan/phase-9-production-hardening.md G6. Acquire before starting work, release in a
 * {@code finally} block; a failed {@link #tryAcquire} means another caller already holds that key.
 */
public final class InFlightGuard<K> {

	private final Set<K> inFlight = ConcurrentHashMap.newKeySet();

	public boolean tryAcquire(K key) {
		return inFlight.add(key);
	}

	public void release(K key) {
		inFlight.remove(key);
	}
}
