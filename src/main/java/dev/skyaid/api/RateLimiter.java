package dev.skyaid.api;

import java.net.http.HttpHeaders;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks Hypixel's rate limit from the response headers it sends back.
 *
 * <p>The default budget is 300 requests per 5 minutes. Rather than counting
 * locally and hoping the two stay in step, this reads the authoritative
 * RateLimit-Remaining and RateLimit-Reset headers off each response, so a key
 * shared with another program still behaves.
 */
public final class RateLimiter {
	/**
	 * Stop issuing requests with this many left, keeping a reserve so an
	 * interactive lookup still works after background refreshes have run.
	 */
	private static final int RESERVE = 5;

	private final AtomicInteger remaining = new AtomicInteger(Integer.MAX_VALUE);
	private final AtomicLong resetAtMillis = new AtomicLong(0);

	/** False when the budget is spent and the window has not yet rolled over. */
	public boolean mayRequest() {
		if (remaining.get() > RESERVE) {
			return true;
		}

		// Budget is low; only allow through once the window has reset.
		return System.currentTimeMillis() >= resetAtMillis.get();
	}

	public void observe(HttpHeaders headers) {
		headers.firstValue("ratelimit-remaining")
				.map(RateLimiter::parseOrNull)
				.ifPresent(remaining::set);

		headers.firstValue("ratelimit-reset")
				.map(RateLimiter::parseOrNull)
				.ifPresent(seconds ->
						resetAtMillis.set(System.currentTimeMillis() + seconds * 1000L));
	}

	/** Called on a 429, which means the local view of the budget was wrong. */
	public void onRateLimited(HttpHeaders headers) {
		remaining.set(0);
		observe(headers);

		if (resetAtMillis.get() <= System.currentTimeMillis()) {
			// No usable reset header - back off for a conservative full window.
			resetAtMillis.set(System.currentTimeMillis() + 60_000L);
		}
	}

	/** The budget Hypixel last reported, for the dump's rate line. */
	public int remainingBudget() {
		int value = remaining.get();
		return value == Integer.MAX_VALUE ? -1 : value;
	}

	private static Integer parseOrNull(String value) {
		try {
			return Integer.valueOf(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
