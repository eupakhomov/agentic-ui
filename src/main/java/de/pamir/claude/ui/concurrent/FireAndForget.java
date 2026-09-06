package de.pamir.claude.ui.concurrent;

import org.slf4j.Logger;

/**
 * "Run this on its own virtual thread; log and swallow a {@link RuntimeException} instead of
 * losing it to an uncaught-exception handler" — the body of every {@code @EventListener} that
 * kicks off best-effort background work (reflection, service discovery). See
 * docs/plan/phase-9-production-hardening.md G6.
 */
public final class FireAndForget {

	private FireAndForget() {
	}

	/**
	 * @param failureContext prefixed to the caught exception's message when logging, e.g.
	 *                        {@code "reflection failed for session " + sessionId}
	 */
	public static void run(String threadName, Logger log, String failureContext, Runnable task) {
		Thread.ofVirtual().name(threadName).start(() -> {
			try {
				task.run();
			} catch (RuntimeException e) {
				log.warn("{}: {}", failureContext, e.getMessage());
			}
		});
	}
}
