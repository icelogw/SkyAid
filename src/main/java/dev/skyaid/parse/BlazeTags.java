package dev.skyaid.parse;

import java.util.OptionalLong;

/**
 * Reads a blaze's health out of its floating name tag in the blaze puzzle -
 * "[Lv15] Blaze 20,000,000/25,000,000❤" or the current-only "Blaze 19,340❤".
 *
 * <p>Free of Minecraft types so the parsing is testable: the tag text is the
 * only ground truth for the kill order, and misreading it points the player at
 * the wrong blaze.
 */
public final class BlazeTags {
	private BlazeTags() {
	}

	/** The blaze's current health, or empty when this is not a blaze tag. */
	public static OptionalLong currentHealth(String rawName) {
		String plain = FormatCodes.strip(rawName);

		if (!plain.contains("Blaze")) {
			return OptionalLong.empty();
		}

		int slash = plain.indexOf('/');

		if (slash > 0) {
			return numberEndingAt(plain, slash);
		}

		int heart = plain.indexOf((char) 0x2764);

		if (heart > 0) {
			return numberEndingAt(plain, heart);
		}

		return OptionalLong.empty();
	}

	/** The comma-grouped number whose last digit sits just before {@code end}. */
	private static OptionalLong numberEndingAt(String text, int end) {
		int start = end;

		while (start > 0) {
			char c = text.charAt(start - 1);

			if ((c >= '0' && c <= '9') || c == ',') {
				start--;
			} else {
				break;
			}
		}

		String digits = text.substring(start, end).replace(",", "");

		if (digits.isEmpty()) {
			return OptionalLong.empty();
		}

		try {
			return OptionalLong.of(Long.parseLong(digits));
		} catch (NumberFormatException e) {
			return OptionalLong.empty();
		}
	}
}
