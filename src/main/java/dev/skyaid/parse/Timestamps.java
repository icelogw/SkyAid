package dev.skyaid.parse;

import java.time.LocalTime;
import java.util.Locale;

/**
 * Formats the clock shown in front of chat lines.
 *
 * <p>Written by hand rather than with a DateTimeFormatter pattern for two
 * reasons: a pattern's AM/PM marker is locale-dependent and uppercase, and
 * midnight and noon are the classic places a twelve-hour clock goes wrong -
 * both land on hour 12, and a naive modulo turns midnight into "0:05am". Being
 * plain arithmetic here means those cases can be unit tested.
 */
public final class Timestamps {
	private Timestamps() {
	}

	public static String format(LocalTime time, boolean twelveHour) {
		int hour = time.getHour();
		int minute = time.getMinute();

		if (!twelveHour) {
			return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
		}

		int displayHour = hour % 12;

		// Hour 0 and hour 12 both map to 12 on a twelve-hour clock: midnight is
		// 12am, noon is 12pm. Without this they would read as "0:05am".
		if (displayHour == 0) {
			displayHour = 12;
		}

		return String.format(Locale.ROOT, "%d:%02d%s",
				displayHour, minute, hour < 12 ? "am" : "pm");
	}
}
