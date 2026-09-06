package de.pamir.claude.ui.discovery;

import java.util.UUID;

/**
 * Published by {@code SessionService.close()} when {@code service-discovery.enabled}, consumed by
 * {@link ServiceDiscoveryService} on a virtual thread — same shape as memory's
 * {@code ReflectionRequested} (docs/plan/phase-5.3-memory-reflection.md), avoiding a dependency
 * from SessionService back onto this feature (see docs/plan/phase-8-service-discovery.md).
 */
public record ServiceDiscoveryRequested(UUID sessionId, String repoPath) {
}
