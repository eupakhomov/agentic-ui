package de.pamir.agentic.ui.integration;

import de.pamir.agentic.ui.integration.TicketImportService.TicketImportResult;
import de.pamir.agentic.ui.integration.TicketImportService.TicketList;
import de.pamir.agentic.ui.integration.TicketImportService.TicketSummary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * In-memory TTL cache backing {@link TicketImportService} — see
 * docs/plan/phase-12-linear-cache-serena-context.md Step A1. A restart clears it, which is fine:
 * the cached data is an LLM-generated summary that's stale by definition, cheap to regenerate
 * relative to everything else the backend does, and not worth persisting.
 *
 * <p>The ticket list is single-flighted: concurrent callers while a fetch is already running join
 * that one fetch's result instead of starting a second system turn (the system-session lock would
 * serialize a second turn anyway, but it would still run). Import results are keyed independently
 * per ticket ref, so two different tickets fetched at once are legitimately two turns.
 */
class TicketCache {

	static final Duration TTL = Duration.ofMinutes(15);

	private record ListEntry(TicketList list, boolean oauthMode) {
	}

	private record ImportEntry(TicketImportResult result, Instant fetchedAt) {
	}

	private final Clock clock;
	private final AtomicReference<ListEntry> listEntry = new AtomicReference<>();
	private final AtomicReference<CompletableFuture<List<TicketSummary>>> listInFlight = new AtomicReference<>();
	private final Map<String, ImportEntry> importEntries = new ConcurrentHashMap<>();

	TicketCache(Clock clock) {
		this.clock = clock;
	}

	/**
	 * The cached list if it's still fresh and was fetched under the same OAuth mode, else
	 * {@code null} (a mode flip is treated as a miss rather than plumbing a settings-change
	 * listener — see decision 5 in the phase doc).
	 */
	TicketList freshList(boolean oauthMode) {
		ListEntry e = listEntry.get();
		if (e == null || e.oauthMode() != oauthMode || isExpired(e.list().fetchedAt())) {
			return null;
		}
		return new TicketList(e.list().tickets(), e.list().fetchedAt(), true);
	}

	/**
	 * Fetches a fresh list via {@code fetch}, sharing the result with any other caller that calls
	 * this concurrently while the fetch is still running. A failed fetch is not cached — a failed
	 * pre-warm must not poison the cache for the next real request.
	 */
	TicketList fetchList(boolean oauthMode, Supplier<List<TicketSummary>> fetch) {
		CompletableFuture<List<TicketSummary>> mine = new CompletableFuture<>();
		CompletableFuture<List<TicketSummary>> existing = listInFlight.compareAndExchange(null, mine);
		if (existing != null) {
			return new TicketList(join(existing), clock.instant(), false);
		}
		try {
			List<TicketSummary> tickets = fetch.get();
			mine.complete(tickets);
			TicketList result = new TicketList(tickets, clock.instant(), false);
			listEntry.set(new ListEntry(result, oauthMode));
			return result;
		} catch (RuntimeException e) {
			mine.completeExceptionally(e);
			throw e;
		} finally {
			listInFlight.compareAndSet(mine, null);
		}
	}

	private static List<TicketSummary> join(CompletableFuture<List<TicketSummary>> future) {
		try {
			return future.join();
		} catch (CompletionException e) {
			if (e.getCause() instanceof RuntimeException re) {
				throw re;
			}
			throw e;
		}
	}

	/** Cached import result for {@code key} (already the caller's canonical form) if still fresh. */
	TicketImportResult cachedImport(String key) {
		ImportEntry e = importEntries.get(key);
		if (e == null || isExpired(e.fetchedAt())) {
			return null;
		}
		return e.result();
	}

	void cacheImport(String key, TicketImportResult result) {
		importEntries.put(key, new ImportEntry(result, clock.instant()));
	}

	private boolean isExpired(Instant fetchedAt) {
		return !clock.instant().isBefore(fetchedAt.plus(TTL));
	}

	void clear() {
		listEntry.set(null);
		importEntries.clear();
	}
}
