package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.TabListReader;
import dev.skyaid.parse.FormatCodes;

/**
 * One shared answer to "is the player in the Crystal Hollows right now",
 * read from the tab list's Area line and memoised for a second - three
 * features ask every tick.
 */
public final class CrystalHollows {
	private static long checkedAt;
	private static boolean inside;

	private CrystalHollows() {
	}

	public static synchronized boolean inCrystalHollows() {
		if (ConfigManager.get().debug) {
			return true;
		}

		long now = System.currentTimeMillis();

		if (now - checkedAt < 1000) {
			return inside;
		}

		checkedAt = now;
		inside = false;

		for (String raw : TabListReader.lines()) {
			String line = FormatCodes.strip(raw).trim();

			if (line.startsWith("Area:")) {
				inside = line.contains("Crystal Hollows");
				break;
			}
		}

		return inside;
	}
}
