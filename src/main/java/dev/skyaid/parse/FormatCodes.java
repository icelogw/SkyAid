package dev.skyaid.parse;

/**
 * Strips Minecraft formatting codes and invisible padding from text.
 *
 * <p>Hypixel splits each sidebar line across a team prefix, the score holder's
 * name and a team suffix, and pads the joins with invisible characters so that
 * otherwise-identical lines stay distinct. Those joins can land in the middle of
 * a number: a real capture showed a purse of 7,884,262 arriving with a character
 * wedged between the "2" and the "62", which truncated parsing to 78,842.
 *
 * <p>So this does not work from a list of known offenders. It removes anything
 * Unicode classifies as a format or control character, which covers every
 * zero-width space, directional mark and byte-order mark at once, plus any
 * section-sign code including ones this code has never seen.
 */
public final class FormatCodes {
	/** U+00A7 SECTION SIGN, which introduces a Minecraft formatting code. */
	private static final char SECTION = (char) 0x00A7;

	/** U+00A0 NO-BREAK SPACE, which Hypixel uses in place of a space in some lines. */
	private static final char NBSP = (char) 0x00A0;

	private FormatCodes() {
	}

	/**
	 * Removes formatting codes and invisible characters, normalises whitespace and
	 * trims. Returns an empty string for null or empty input so callers do not have
	 * to null-check every sidebar line.
	 */
	public static String strip(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}

		StringBuilder out = new StringBuilder(text.length());
		boolean pendingSpace = false;

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			// A section sign always consumes the character after it, whatever it is.
			// Matching a fixed set of code letters would leave a stray sign behind
			// when Hypixel uses one this code does not know about.
			if (c == SECTION) {
				i++;
				continue;
			}

			if (c == NBSP || Character.isWhitespace(c)) {
				// Only emit a separator once we know real content follows it.
				pendingSpace = !out.isEmpty();
				continue;
			}

			if (isInvisible(c)) {
				continue;
			}

			if (pendingSpace) {
				out.append(' ');
				pendingSpace = false;
			}

			out.append(c);
		}

		return out.toString();
	}

	/**
	 * True for characters that occupy no visual space. FORMAT covers the
	 * zero-width and directional marks (U+200B..U+200F, U+202A..U+202E, U+FEFF and
	 * friends); CONTROL and the unassigned/private-use categories catch the rest of
	 * what turns up in server-built scoreboards.
	 */
	private static boolean isInvisible(char c) {
		return switch (Character.getType(c)) {
			case Character.FORMAT, Character.CONTROL, Character.UNASSIGNED -> true;
			default -> false;
		};
	}
}
