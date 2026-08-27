package dev.skyaid.parse;

import java.util.Optional;

/**
 * Finds shared coordinates in a chat line, so a teammate's callout can become
 * a waypoint automatically.
 *
 * <p>Matches the "x: 123, y: 70, z: -45" shape - the format SkyAid's own
 * share-location keybind prefills, and the common way players call positions
 * out. Deliberately strict: each axis letter must start a word, so "max: 5"
 * can never read as an x coordinate.
 */
public final class ChatCoords {
	/**
	 * @param label where the marker came from: the "(Village)" zone note when
	 *              the callout carries one, else the sender's name, else "Shared"
	 */
	public record Shared(String label, int x, int y, int z) {
	}

	private ChatCoords() {
	}

	public static Optional<Shared> parse(String rawMessage) {
		String plain = FormatCodes.strip(rawMessage);

		int xAt = axisIndex(plain, 'x', 0);

		if (xAt < 0) {
			return Optional.empty();
		}

		Long x = numberAfter(plain, xAt + 2);
		int yAt = axisIndex(plain, 'y', xAt);
		Long y = yAt < 0 ? null : numberAfter(plain, yAt + 2);
		int zAt = yAt < 0 ? -1 : axisIndex(plain, 'z', yAt);
		Long z = zAt < 0 ? null : numberAfter(plain, zAt + 2);

		if (x == null || y == null || z == null) {
			return Optional.empty();
		}

		return Optional.of(new Shared(
				label(plain, xAt, zAt),
				x.intValue(), y.intValue(), z.intValue()));
	}

	/** The next "x:" (or y/z) that starts a word, from {@code from} onwards. */
	private static int axisIndex(String text, char axis, int from) {
		for (int i = from; i < text.length() - 1; i++) {
			char c = Character.toLowerCase(text.charAt(i));

			if (c != axis || text.charAt(i + 1) != ':') {
				continue;
			}

			boolean startsWord = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));

			if (startsWord) {
				return i;
			}
		}

		return -1;
	}

	/** The first integer at or after {@code from}, tolerating spaces and a sign. */
	private static Long numberAfter(String text, int from) {
		int i = from;

		while (i < text.length() && text.charAt(i) == ' ') {
			i++;
		}

		int start = i;

		if (i < text.length() && text.charAt(i) == '-') {
			i++;
		}

		int digitsFrom = i;

		while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
			i++;
		}

		if (i == digitsFrom || i - digitsFrom > 7) {
			return null;
		}

		return Long.parseLong(text.substring(start, i));
	}

	/** The "(Village)" note when present, else the word right before the coords. */
	private static String label(String plain, int xAt, int zAt) {
		int open = plain.indexOf('(', zAt);
		int close = open < 0 ? -1 : plain.indexOf(')', open);

		if (open >= 0 && close > open + 1) {
			return plain.substring(open + 1, close).trim();
		}

		// "Party > [MVP+] Notch: x: ..." - the sender's name is the last word
		// before the coordinates, minus the colon that ends it.
		String prefix = plain.substring(0, xAt).trim();

		while (prefix.endsWith(":")) {
			prefix = prefix.substring(0, prefix.length() - 1).trim();
		}

		int lastSpace = prefix.lastIndexOf(' ');
		String name = lastSpace < 0 ? prefix : prefix.substring(lastSpace + 1);

		return name.isBlank() ? "Shared" : name;
	}
}
