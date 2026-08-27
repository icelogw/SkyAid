package dev.skyaid.parse;

/**
 * Recognises event-countdown lines on the sidebar, such as
 * "Carnival 111:58:05" - a name followed by a clock.
 *
 * <p>Used to lift event timers out of the raw passthrough lines into their own
 * HUD element. Verified against the real Carnival line captured 2026-08-20.
 */
public final class EventTimers {
	private EventTimers() {
	}

	/** Whether this line is "some name" followed by a H:MM:SS or MM:SS clock. */
	public static boolean isTimer(String line) {
		String plain = FormatCodes.strip(line).trim();
		int lastSpace = plain.lastIndexOf(' ');

		if (lastSpace <= 0) {
			return false;
		}

		return isClock(plain.substring(lastSpace + 1));
	}

	/** "111:58:05", "58:05" - two or three groups, the trailing ones two digits. */
	static boolean isClock(String token) {
		String[] parts = token.split(":");

		if (parts.length < 2 || parts.length > 3) {
			return false;
		}

		if (parts[0].isEmpty() || parts[0].length() > 4 || !digits(parts[0])) {
			return false;
		}

		for (int i = 1; i < parts.length; i++) {
			if (parts[i].length() != 2 || !digits(parts[i])) {
				return false;
			}
		}

		return true;
	}

	private static boolean digits(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (c < '0' || c > '9') {
				return false;
			}
		}

		return true;
	}
}
