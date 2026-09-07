package de.pamir.claude.ui.process;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Constructs a real {@link SidecarHandle} over an in-memory fake {@link Process} — no real OS
 * subprocess, no Mockito — so SessionService's dispatch/queue-drain/terminate paths can be
 * unit-tested (see docs/plan/phase-9-production-hardening.md T2). Lives in this package (not a
 * generic test-support package) because {@code SidecarHandle}'s constructor is package-private
 * by design; everything else about a fake sidecar can be built from public API alone.
 */
public final class FakeSidecar {

	private FakeSidecar() {
	}

	/** A handle whose stdin writes are discarded. */
	public static SidecarHandle newHandle(UUID sessionId, Consumer<JsonNode> onEvent,
										   BiConsumer<SidecarHandle, Integer> onExit) {
		SidecarHandle handle = new SidecarHandle(sessionId, new FakeProcess(null), new JsonMapper(), null, onEvent, onExit);
		handle.watchExit();
		return handle;
	}

	/** Same, but every line written to stdin (one send() call each) is appended to {@code sentLines}. */
	public static SidecarHandle newHandle(UUID sessionId, List<String> sentLines, Consumer<JsonNode> onEvent,
										   BiConsumer<SidecarHandle, Integer> onExit) {
		SidecarHandle handle = new SidecarHandle(sessionId, new FakeProcess(sentLines), new JsonMapper(), null, onEvent, onExit);
		handle.watchExit();
		return handle;
	}

	private static final class FakeProcess extends Process {
		private final OutputStream stdin;
		private final CountDownLatch destroyed = new CountDownLatch(1);

		FakeProcess(List<String> sentLines) {
			this.stdin = sentLines == null ? OutputStream.nullOutputStream() : new LineCapturingStream(sentLines);
		}

		@Override
		public OutputStream getOutputStream() {
			return stdin;
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
		public int waitFor() throws InterruptedException {
			destroyed.await();
			return 0;
		}

		@Override
		public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) {
			// no real OS timing to simulate: either already destroyed, or still running — no
			// point actually sleeping out the caller's timeout (SidecarHandle.terminate() would
			// otherwise block every test for several real seconds waiting on a process that was
			// never going to exit on its own).
			return !isAlive();
		}

		@Override
		public int exitValue() {
			if (destroyed.getCount() > 0) {
				throw new IllegalThreadStateException("process hasn't exited");
			}
			return 0;
		}

		@Override
		public void destroy() {
			destroyed.countDown();
		}

		@Override
		public boolean isAlive() {
			return destroyed.getCount() > 0;
		}

		@Override
		public Stream<ProcessHandle> descendants() {
			return Stream.empty();
		}
	}

	/** Buffers bytes and splits on '\n' so each full line written via send() lands in the list whole. */
	private static final class LineCapturingStream extends OutputStream {
		private final List<String> sentLines;
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		LineCapturingStream(List<String> sentLines) {
			this.sentLines = sentLines;
		}

		@Override
		public synchronized void write(int b) {
			if (b == '\n') {
				sentLines.add(buffer.toString(StandardCharsets.UTF_8));
				buffer.reset();
			} else {
				buffer.write(b);
			}
		}
	}
}
