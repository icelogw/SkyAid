package dev.skyaid.parse;

import java.util.OptionalLong;

/**
 * Running totals for the current play session: coins and bits gained, and how
 * long was actually spent in Skyblock.
 *
 * <p>Fed sidebar observations by the tracker. Lives here, free of Minecraft
 * types, so the arithmetic can be unit tested - and time is passed in rather
 * than read from the clock for the same reason.
 *
 * <p>Two things are deliberate about how time is counted. First, only the time
 * between consecutive observations counts, and a long gap is capped at
 * {@link #MAX_GAP_MILLIS}: observations stop while the player is in a lobby or
 * logged out, so time away naturally does not count towards coins/hour. Second,
 * gains are net change, not income - spending coins pulls the figure down.
 * Every session tracker works this way, because the sidebar only shows the
 * balance, never the reason it moved.
 */
public final class SessionStats {
	/**
	 * The longest stretch a single observation gap may add to the session clock.
	 * Observations arrive many times a second while in Skyblock, so anything
	 * beyond this means the player was elsewhere and that time must not count.
	 */
	static final long MAX_GAP_MILLIS = 1_000;

	/** Coins/hour extrapolated from less than this is noise, so it is withheld. */
	static final long RATE_AFTER_MILLIS = 60_000;

	/**
	 * How much purse history is kept, bounding the sample deque. Matches the
	 * longest window the settings slider offers.
	 */
	static final long RETENTION_MILLIS = 3_600_000;

	private long activeMillis;
	private long lastObservedMillis = -1;

	/**
	 * Purse samples as {activeTime, purse, bankFlow}, one per second of active
	 * time, for the recent rate. Keyed to the active clock rather than the wall
	 * clock so time in a lobby neither ages samples out nor dilutes the window.
	 * Bank flow rides along so a transfer inside the window cancels out of the
	 * windowed rate exactly as it does out of the session total.
	 */
	private final java.util.ArrayDeque<long[]> purseSamples = new java.util.ArrayDeque<>();

	/**
	 * Net coins moved from the bank into the purse. Deposits and withdrawals
	 * change the purse without anything being earned, so this is subtracted
	 * back out of every gain figure. Fed from Hypixel's own chat confirmations.
	 */
	private long bankFlow;

	private boolean purseSeen;
	private long purseStart;
	private long purseLast;

	private boolean bitsSeen;
	private long bitsStart;
	private long bitsLast;

	/**
	 * Records one sidebar reading. The first sighting of purse or bits becomes
	 * that value's baseline; a reading where a value is absent (bits outside the
	 * hub, purse in dungeons) keeps the last known figure rather than treating
	 * the absence as zero.
	 */
	public void observe(long nowMillis, OptionalLong purse, OptionalLong bits) {
		if (lastObservedMillis >= 0) {
			long gap = nowMillis - lastObservedMillis;

			if (gap > 0) {
				activeMillis += Math.min(gap, MAX_GAP_MILLIS);
			}
		}

		lastObservedMillis = nowMillis;

		if (purse.isPresent()) {
			if (!purseSeen) {
				purseSeen = true;
				purseStart = purse.getAsLong();
			}

			purseLast = purse.getAsLong();

			if (purseSamples.isEmpty()
					|| activeMillis - purseSamples.peekLast()[0] >= 1_000) {
				purseSamples.addLast(new long[] {activeMillis, purseLast, bankFlow});
			}

			while (purseSamples.size() > 1
					&& purseSamples.peekFirst()[0] < activeMillis - RETENTION_MILLIS) {
				purseSamples.removeFirst();
			}
		}

		if (bits.isPresent()) {
			if (!bitsSeen) {
				bitsSeen = true;
				bitsStart = bits.getAsLong();
			}

			bitsLast = bits.getAsLong();
		}
	}

	/** Coins/hour averaged over the whole session. */
	public Snapshot snapshot() {
		return snapshot(0);
	}

	/**
	 * Coins/hour averaged over the last {@code rateWindowMillis} of active time,
	 * or over the whole session when the window is zero or the session is still
	 * shorter than it. A windowed rate answers "what is this spot making now"
	 * where the session average sinks slowly all through a quiet stretch.
	 */
	/** Records a bank transfer: positive into the purse, negative out of it. */
	public void observeBankTransfer(long intoPurse) {
		bankFlow += intoPurse;
	}

	public Snapshot snapshot(long rateWindowMillis) {
		OptionalLong coins = purseSeen
				? OptionalLong.of(purseLast - purseStart - bankFlow)
				: OptionalLong.empty();
		OptionalLong bits = bitsSeen
				? OptionalLong.of(bitsLast - bitsStart)
				: OptionalLong.empty();

		return new Snapshot(coins, bits, rate(rateWindowMillis), activeMillis);
	}

	private OptionalLong rate(long windowMillis) {
		if (!purseSeen) {
			return OptionalLong.empty();
		}

		// The window's baseline is the newest sample at or before its start, so
		// the measured span is never artificially shorter than the window.
		long baselineTime = 0;
		long baselinePurse = purseStart;
		long baselineFlow = 0;

		if (windowMillis > 0) {
			long cutoff = activeMillis - windowMillis;

			for (long[] sample : purseSamples) {
				if (sample[0] > cutoff) {
					break;
				}

				baselineTime = sample[0];
				baselinePurse = sample[1];
				baselineFlow = sample[2];
			}
		}

		long elapsed = activeMillis - baselineTime;

		if (elapsed < RATE_AFTER_MILLIS) {
			return OptionalLong.empty();
		}

		long gained = (purseLast - baselinePurse) - (bankFlow - baselineFlow);

		return OptionalLong.of(Math.round(gained * 3_600_000.0 / elapsed));
	}

	/**
	 * An immutable reading of the session, safe to hand to the render thread.
	 *
	 * <p>Coins and bits are empty until their sidebar line has been seen at least
	 * once - the same "absent is not zero" rule as {@link SkyblockState}. The
	 * rate stays empty until {@link #RATE_AFTER_MILLIS} of play, since a figure
	 * extrapolated from seconds swings wildly enough to be worse than nothing.
	 */
	public record Snapshot(
			OptionalLong coinsGained,
			OptionalLong bitsGained,
			OptionalLong coinsPerHour,
			long activeMillis) {

		public static final Snapshot EMPTY = new Snapshot(
				OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), 0);

		/** Whether any Skyblock time has been observed at all. */
		public boolean started() {
			return activeMillis > 0;
		}

		/** The active time as "38s", "4m" or "1h 05m" - HUD-sized, not exact. */
		public String formattedDuration() {
			return TimeSpans.brief(activeMillis);
		}
	}
}
