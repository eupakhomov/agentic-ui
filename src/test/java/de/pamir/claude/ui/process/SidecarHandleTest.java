package de.pamir.claude.ui.process;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the ordering fix from docs/plan/phase-10-review-followups.md R5: {@link
 * SidecarHandle#watchExit()} must only be called after the caller (in production,
 * {@code SidecarManager.spawn}) has already inserted the handle into its own bookkeeping map —
 * otherwise a process that's dead on arrival fires the exit callback before there's anything to
 * remove, leaving a dead entry behind. Exercised here at the {@code SidecarHandle} level directly
 * (same package, for constructor access) rather than through a real {@code SidecarManager} spawn,
 * since that would require a real OS subprocess to reproduce deterministically.
 */
class SidecarHandleTest {

	@Test
	void watchExitAfterMapInsertLeavesNoDeadEntryWhenProcessIsAlreadyDead() {
		Map<UUID, SidecarHandle> handles = new ConcurrentHashMap<>();
		UUID id = UUID.randomUUID();
		SidecarHandle handle = new SidecarHandle(id, new DeadOnArrivalProcess(), new JsonMapper(), null,
				event -> {
				}, (h, code) -> handles.remove(id, h));

		// mirrors SidecarManager.spawn's fixed ordering: insert before watching for exit
		handles.put(id, handle);
		handle.watchExit();

		assertThat(handles).doesNotContainKey(id);
	}

	/** A process that has already exited by the time anyone asks — onExit() completes synchronously. */
	private static final class DeadOnArrivalProcess extends Process {

		@Override
		public OutputStream getOutputStream() {
			return OutputStream.nullOutputStream();
		}

		@Override
		public InputStream getInputStream() {
			return InputStream.nullInputStream();
		}

		@Override
		public InputStream getErrorStream() {
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor() {
			return 0;
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit) {
			return true;
		}

		@Override
		public int exitValue() {
			return 1;
		}

		@Override
		public void destroy() {
		}

		@Override
		public boolean isAlive() {
			return false;
		}

		@Override
		public Stream<ProcessHandle> descendants() {
			return Stream.empty();
		}

		@Override
		public CompletableFuture<Process> onExit() {
			// unlike the JDK default (which waits on a background thread), this completes
			// synchronously so the test assertion right after watchExit() is deterministic
			return CompletableFuture.completedFuture(this);
		}
	}
}
