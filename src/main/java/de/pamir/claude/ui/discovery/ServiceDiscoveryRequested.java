package de.pamir.claude.ui.discovery;

import java.util.UUID;

/**
 * Published by {@code SessionService.close()} when {@code service-discovery.enabled}, consumed by
 * {@link ServiceDiscoveryService} on a virtual thread — same shape as memory's
 * {@code ReflectionRequested} (docs/plan/phase-5.3-memory-reflection.md), avoiding a dependency
 * from SessionService back onto this feature (see docs/plan/phase-8-service-discovery.md).
 * {@code servicePath} is the identity/scope (equal to {@code repoPath} for a polyrepo session);
 * {@code repoPath} is the git root, needed to compute the staleness SHA
 * (docs/plan/phase-11-monorepo.md Step 5).
 */
public record ServiceDiscoveryRequested(UUID sessionId, String servicePath, String repoPath) {
}
