package dev.skyaid.parse;

import java.util.Locale;

/**
 * The Garden's readouts. Two jobs, both pure: recognising Garden-flavoured
 * status lines (sidebar or tab list) so the HUD's garden element can claim
 * them, and the row-alignment readout that turns yaw into "how far off a
 * straight farming line am I".
 *
 * <p>The line prefixes are ecosystem knowledge, UNVERIFIED against captures -
 * a Garden dump corrects them.
 */
public final class GardenLines {
	/** Garden widget prefixes as Hypixel writes them (assumed). */
	private static final String[] PREFIXES = {
			"Milestone", "Visitors", "Next Visitor", "Pests", "Plots",
			"Composter", "Jacob", "Contest", "Your Insta-Sells",
			// Screenshot-verified 2026-08-25: both pass through the sidebar.
			"Copper", "Sowdust"};

	private GardenLines() {
	}

	/** Tab-list widget lines worth lifting (dump-verified 2026-08-25). */
	private static final String[] TAB_STATS = {
			"Garden Level", "Bonus Pest Chance", "Pests", "Visitors",
			"Next Visitor"};

	public static boolean isTabStat(String line) {
		String trimmed = line.trim();

		for (String prefix : TAB_STATS) {
			if (trimmed.startsWith(prefix)) {
				return true;
			}
		}

		// " Sugar Cane Fortune: 99", "Farming Fortune: 240" - the family
		// is open-ended, the shape is not.
		return trimmed.contains("Fortune: ");
	}

	/** Hypixel's private-use icon glyphs, dropped so numbers read clean. */
	public static String stripIcons(String text) {
		StringBuilder out = new StringBuilder(text.length());

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (c < 0xE000 || c > 0xF8FF) {
				out.append(c);
			}
		}

		return out.toString();
	}

	public static boolean isGardenLine(String line) {
		String trimmed = line.trim();

		for (String prefix : PREFIXES) {
			if (trimmed.startsWith(prefix)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * The alignment readout: yaw normalised to 0-360, the offset from the
	 * nearest 45-degree row direction, and pitch. Farming rows want an exact
	 * direction; the offset makes lining up a glance instead of a guess.
	 *
	 * <p>Minecraft yaw: 0 = south, 90 = west, 180 = north, 270 = east.
	 */
	public static String angle(float rawYaw, float pitch) {
		double yaw = ((rawYaw % 360) + 360) % 360;
		double nearest = Math.round(yaw / 45.0) * 45.0;
		double off = yaw - nearest;

		String direction = switch (((int) nearest % 360) / 45) {
			case 0 -> "S";
			case 1 -> "SW";
			case 2 -> "W";
			case 3 -> "NW";
			case 4 -> "N";
			case 5 -> "NE";
			case 6 -> "E";
			case 7 -> "SE";
			default -> "?";
		};

		return String.format(Locale.ROOT, "Yaw %.1f (%+.1f off %s)  Pitch %.1f",
				yaw, off, direction, pitch);
	}
}
