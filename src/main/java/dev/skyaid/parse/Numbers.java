package dev.skyaid.parse;

import java.util.Locale;

/**
 * Number formatting for the HUD.
 *
 * <p>Lives here, free of Minecraft types, so both forms can be unit tested -
 * rounding at unit boundaries is exactly the sort of thing that looks right until
 * someone has 999,999 coins.
 */
public final class Numbers {
	private static final String[] UNITS = {"k", "M", "B", "T"};

	private Numbers() {
	}

	public static String format(long value, boolean shortForm) {
		return shortForm ? shorten(value) : group(value);
	}

	/**
	 * Groups digits with commas: 7884267 becomes "7,884,267".
	 *
	 * <p>Done by hand rather than with NumberFormat so the separator does not
	 * change with the player's system locale - the HUD replaces Hypixel's own
	 * comma-grouped numbers and should match them.
	 */
	public static String group(long value) {
		String digits = Long.toString(Math.abs(value));
		StringBuilder out = new StringBuilder();

		for (int i = 0; i < digits.length(); i++) {
			if (i > 0 && (digits.length() - i) % 3 == 0) {
				out.append(',');
			}

			out.append(digits.charAt(i));
		}

		return value < 0 ? "-" + out : out.toString();
	}

	/**
	 * Abbreviates to one decimal place: 10000 becomes "10k", 15615 "15.6k",
	 * 7884267 "7.9M". Values under a thousand are left alone.
	 *
	 * <p>A whole-number result drops the ".0" so round figures read as "10k" rather
	 * than "10.0k".
	 */
	public static String shorten(long value) {
		long magnitude = Math.abs(value);

		if (magnitude < 1000) {
			return Long.toString(value);
		}

		double scaled = magnitude;
		int unit = 0;

		while (scaled >= 1000.0 && unit < UNITS.length) {
			scaled /= 1000.0;
			unit++;
		}

		double rounded = Math.round(scaled * 10.0) / 10.0;

		// Rounding can push a value up into the next unit - 999,999 rounds to
		// 1000.0k, which should read as 1M rather than 1000k.
		if (rounded >= 1000.0 && unit < UNITS.length) {
			rounded /= 1000.0;
			unit++;
		}

		String text = String.format(Locale.ROOT, "%.1f", rounded);

		if (text.endsWith(".0")) {
			text = text.substring(0, text.length() - 2);
		}

		return (value < 0 ? "-" : "") + text + UNITS[unit - 1];
	}
}
