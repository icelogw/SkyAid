package dev.skyaid.parse;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Picks the Skyblock objective out of the boss bar texts.
 *
 * <p>Verified against a real capture (2026-08-20): the zone quest shows as a
 * boss bar reading "Objective: Talk to Fisherwoman Enid." - it never appears on
 * the sidebar, which is why the sidebar-based Objective matcher found nothing.
 */
public final class BossBars {
	private static final String HEADING = "Objective";

	private BossBars() {
	}

	/** The active objective, e.g. "Talk to Fisherwoman Enid.", verbatim. */
	public static Optional<String> objective(List<String> barNames) {
		for (String name : barNames) {
			if (!isObjective(name)) {
				continue;
			}

			String value = plain(name).substring(HEADING.length()).trim();

			if (value.startsWith(":")) {
				value = value.substring(1).trim();
			}

			if (!value.isEmpty()) {
				return Optional.of(value);
			}
		}

		return Optional.empty();
	}

	/** Whether this bar is the zone-quest objective banner. */
	public static boolean isObjective(String barName) {
		return plain(barName).startsWith(HEADING);
	}

	/**
	 * Whether this bar is a store or website advert - matched on the address,
	 * the same rule the chat cleanup uses, so talk about Hypixel is never
	 * mistaken for an advert by Hypixel.
	 */
	public static boolean isAdvert(String barName) {
		return plain(barName).toLowerCase(Locale.ROOT).contains("hypixel.net");
	}

	private static String plain(String barName) {
		return FormatCodes.strip(barName).trim();
	}
}
