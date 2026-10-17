package de.pamir.agentic.ui.slack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Recording {@link SlackMessaging} double with scriptable failures and an optional gate that holds the worker. */
final class FakeSlack implements SlackMessaging {

	record Call(String kind, String channel, String ts, SlackMessage message, String extra) {
	}

	final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
	private final AtomicInteger nextTs = new AtomicInteger(1);

	/** When set, every call waits for this latch first — lets a test pile work up behind the worker. */
	volatile CountDownLatch gate;
	/** Errors to throw, in order, from successive calls (consumed one per call; null = succeed). */
	final List<SlackApiException> failures = Collections.synchronizedList(new ArrayList<>());

	private void enter() {
		CountDownLatch g = gate;
		if (g != null) {
			try {
				g.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		synchronized (failures) {
			if (!failures.isEmpty()) {
				SlackApiException e = failures.remove(0);
				if (e != null) {
					throw e;
				}
			}
		}
	}

	volatile String botUser = "UBOT";

	@Override
	public String botUserId() {
		return botUser;
	}

	@Override
	public String postMessage(String channel, String threadTs, SlackMessage message) {
		enter();
		String ts = "100." + nextTs.getAndIncrement();
		calls.add(new Call("post", channel, ts, message, threadTs));
		return ts;
	}

	@Override
	public void updateMessage(String channel, String ts, SlackMessage message) {
		enter();
		calls.add(new Call("update", channel, ts, message, null));
	}

	@Override
	public void addReaction(String channel, String ts, String name) {
		enter();
		calls.add(new Call("react", channel, ts, null, name));
	}

	@Override
	public void respondEphemeral(String responseUrl, String text) {
		enter();
		calls.add(new Call("ephemeral", null, null, null, responseUrl + "|" + text));
	}

	@Override
	public void uploadSnippet(String channel, String threadTs, String title, String content) {
		enter();
		calls.add(new Call("snippet", channel, null, null, title + "|" + threadTs + "|" + content));
	}

	List<Call> of(String kind) {
		synchronized (calls) {
			return calls.stream().filter(c -> c.kind().equals(kind)).toList();
		}
	}

	static void await(BooleanSupplier condition) {
		long end = System.currentTimeMillis() + 5_000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > end) {
				throw new AssertionError("condition not met within 5s");
			}
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		}
	}
}
