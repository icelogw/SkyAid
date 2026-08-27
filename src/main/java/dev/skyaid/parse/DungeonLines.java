package dev.skyaid.parse;

import java.util.OptionalLong;

/**
 * Touch-ups for the dungeon party lines that pass through to the HUD, such as
 * "[B] G00PED 1,586❤" - class tag, name, health.
 *
 * <p>Only the health number is reformatted, and only when short numbers are on:
 * "1,586" becomes "1.6k" so the party block reads at a glance. The trailing
 * glyph is kept exactly as Hypixel sent it - its code point varies, so nothing
 * here assumes what a heart looks like.
 */
public final class DungeonLines {
	private DungeonLines() {
	}

	/**
	 * The line with its trailing health shortened, or unchanged when it is not
	 * a "[X] name number" party line (lobby "[Lv7]" lines, "Cleared: 20%",
	 * anything else).
	 */
	public static String withShortHealth(String line, boolean shortForm) {
		if (!shortForm || !isPartyLine(line)) {
			return line;
		}

		int lastSpace = line.lastIndexOf(' ');

		if (lastSpace <= 0) {
			return line;
		}

		String token = line.substring(lastSpace + 1);

		int digitsEnd = 0;

		while (digitsEnd < token.length() && isNumberChar(token.charAt(digitsEnd))) {
			digitsEnd++;
		}

		if (digitsEnd == 0) {
			return line;
		}

		OptionalLong health = asNumber(token.substring(0, digitsEnd));

		if (health.isEmpty() || health.getAsLong() < 1_000) {
			return line;
		}

		return line.substring(0, lastSpace + 1)
				+ Numbers.shorten(health.getAsLong())
				+ token.substring(digitsEnd);
	}

	/**
	 * The dungeon progress lines - Keys, Time Elapsed, Cleared - as captured
	 * from a live F3 run. The HUD lifts them into their own element.
	 */
	public static boolean isDungeonStat(String line) {
		String plain = FormatCodes.strip(line).trim();

		return plain.startsWith("Keys:")
				|| plain.startsWith("Time Elapsed:")
				|| plain.startsWith("Cleared:");
	}

	/**
	 * "[B] ..." - a single class letter in brackets, then a space. These are the
	 * dungeon party members, which the HUD lifts into their own element.
	 */
	public static boolean isPartyLine(String line) {
		return line.length() > 4
				&& line.charAt(0) == '['
				&& line.charAt(1) >= 'A' && line.charAt(1) <= 'Z'
				&& line.charAt(2) == ']'
				&& line.charAt(3) == ' ';
	}

	private static boolean isNumberChar(char c) {
		return (c >= '0' && c <= '9') || c == ',';
	}

	private static OptionalLong asNumber(String digits) {
		String cleaned = digits.replace(",", "");

		if (cleaned.isEmpty()) {
			return OptionalLong.empty();
		}

		try {
			return OptionalLong.of(Long.parseLong(cleaned));
		} catch (NumberFormatException e) {
			return OptionalLong.empty();
		}
	}
}
