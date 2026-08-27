package dev.skyaid.parse;

import java.util.OptionalLong;

/**
 * Times slayer boss fights off the sidebar's status line: the moment it says
 * the boss is up a stopwatch starts, and the moment it reports the kill the
 * elapsed time is kept as "last kill". Pure state machine - the caller feeds
 * it the parsed status and the clock, tests feed it a fake clock.
 *
 * <p>"Boss slain!" is verified against a real capture; "Slay the boss!" is the
 * ecosystem-known boss-up wording, to be confirmed by the first live fight.
 */
public final class SlayerTimer {
	public static final String BOSS_UP = "Slay the boss!";
	public static final String BOSS_SLAIN = "Boss slain!";

	private long bossSince = -1;
	private long lastKillMillis = -1;

	/** Feed the current slayer status (null when absent) and the clock. */
	public void observe(String status, long now) {
		if (BOSS_UP.equals(status)) {
			if (bossSince < 0) {
				bossSince = now;
			}

			return;
		}

		if (bossSince >= 0 && BOSS_SLAIN.equals(status)) {
			lastKillMillis = now - bossSince;
		}

		// Any non-boss status - questing, slain, or no quest at all - means
		// the stopwatch is not running. The last kill time survives until the
		// next boss starts a new fight or the tracker is reset.
		bossSince = -1;
	}

	/** How long the current boss has been up, while one is. */
	public OptionalLong bossUpFor(long now) {
		return bossSince < 0 ? OptionalLong.empty() : OptionalLong.of(now - bossSince);
	}

	/** How long the previous boss took, once one has been slain. */
	public OptionalLong lastKill() {
		return lastKillMillis < 0 ? OptionalLong.empty() : OptionalLong.of(lastKillMillis);
	}

	/** Forgets everything - for disconnects. */
	public void reset() {
		bossSince = -1;
		lastKillMillis = -1;
	}

	/** "34s" or "1m 12s" - fight-length times, no hours needed. */
	public static String format(long millis) {
		long seconds = millis / 1000;

		if (seconds < 60) {
			return seconds + "s";
		}

		return (seconds / 60) + "m " + (seconds % 60) + "s";
	}
}
