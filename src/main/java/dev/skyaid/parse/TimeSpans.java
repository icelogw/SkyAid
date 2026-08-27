package dev.skyaid.parse;

import java.util.Locale;

/**
 * Short human spans for the HUD and chat: "45s", "4m", "1h 05m".
 *
 * <p>Deliberately coarse - these annotate readouts ("ends in 2h 05m", "3m ago"),
 * where seconds past the first minute are noise.
 */
public final class TimeSpans {
	private TimeSpans() {
	}

	public static String brief(long millis) {
		long minutes = millis / 60_000;

		if (minutes >= 60) {
			return String.format(Locale.ROOT, "%dh %02dm", minutes / 60, minutes % 60);
		}

		if (minutes > 0) {
			return minutes + "m";
		}

		return (millis / 1_000) + "s";
	}
}
