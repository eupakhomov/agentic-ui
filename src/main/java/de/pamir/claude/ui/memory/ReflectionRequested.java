package de.pamir.claude.ui.memory;

import java.util.UUID;

/**
 * Published by SessionService when a reflection-enabled session closes (or via the manual
 * "Reflect now" action), and consumed by ReflectionService. A Spring application event rather
 * than a direct method call so SessionService never depends on ReflectionService (which itself
 * depends on SessionService.runSystemTurn — a direct call the other way would be circular).
 */
public record ReflectionRequested(UUID sessionId) {
}
