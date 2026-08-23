package de.pamir.claude.ui.process;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One live sidecar process: stdin command writer plus virtual-thread readers for
 * stdout (protocol NDJSON) and stderr (rolling tail for crash forensics).
 */
public class SidecarHandle {

	private static final Logger log = LoggerFactory.getLogger(SidecarHandle.class);
	private static final int STDERR_TAIL_LINES = 100;

	private final UUID sessionId;
	private final Process process;
	private final BufferedWriter stdin;
	private final Deque<String> stderrTail = new ArrayDeque<>();
	private volatile boolean shutdownRequested;

	SidecarHandle(UUID sessionId, Process process, ObjectMapper mapper,
				  Consumer<JsonNode> onEvent, BiConsumer<SidecarHandle, Integer> onExit) {
		this.sessionId = sessionId;
		this.process = process;
		this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

		Thread.ofVirtual().name("sidecar-out-" + sessionId).start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.isBlank()) {
						continue;
					}
					try {
						onEvent.accept(mapper.readTree(line));
					} catch (Exception e) {
						log.warn("session {}: bad sidecar stdout line: {}", sessionId, e.getMessage());
					}
				}
			} catch (IOException e) {
				log.debug("session {}: stdout closed: {}", sessionId, e.getMessage());
			}
		});

		Thread.ofVirtual().name("sidecar-err-" + sessionId).start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					synchronized (stderrTail) {
						stderrTail.addLast(line);
						if (stderrTail.size() > STDERR_TAIL_LINES) {
							stderrTail.removeFirst();
						}
					}
				}
			} catch (IOException ignored) {
				// stream closed with the process
			}
		});

		process.onExit().thenAccept(p -> onExit.accept(this, p.exitValue()));
	}

	public synchronized void send(String jsonLine) {
		try {
			stdin.write(jsonLine);
			stdin.write('\n');
			stdin.flush();
		} catch (IOException e) {
			throw new IllegalStateException("sidecar for session " + sessionId + " is not accepting input", e);
		}
	}

	public void markShutdownRequested() {
		shutdownRequested = true;
	}

	public boolean isShutdownRequested() {
		return shutdownRequested;
	}

	public boolean isAlive() {
		return process.isAlive();
	}

	public long pid() {
		return process.pid();
	}

	public List<String> stderrTail() {
		synchronized (stderrTail) {
			return List.copyOf(stderrTail);
		}
	}

	/** Graceful first: shutdown command, then destroy, then forcibly incl. descendants. */
	public void terminate() {
		markShutdownRequested();
		if (!process.isAlive()) {
			return;
		}
		try {
			send("{\"type\":\"shutdown\"}");
		} catch (IllegalStateException ignored) {
			// stdin already closed
		}
		try {
			if (process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
				return;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		process.descendants().forEach(ProcessHandle::destroy);
		process.destroy();
		try {
			if (process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
				return;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
	}
}
