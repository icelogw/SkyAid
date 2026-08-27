package dev.skyaid.parse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out the order of the HUD's lines.
 *
 * <p>Kept free of Minecraft types so the ordering rules can be unit tested. The
 * fiddly part is dividers: a divider is only meaningful between two things, so
 * one stranded at the top, at the bottom, or next to an element that produced
 * nothing has to disappear. That is easy to get wrong and invisible in review.
 */
public final class HudLayout {
	/** Marks a separator rather than a readout. May appear any number of times. */
	public static final String DIVIDER = "divider";

	/**
	 * A removed element is stored as "-id" at the end of the layout rather than
	 * simply dropped. Without the marker, {@link #sanitise} would treat it as an
	 * element from before an upgrade and helpfully append it right back.
	 */
	public static String removalMarker(String id) {
		return "-" + id;
	}

	public static boolean isRemoval(String entry) {
		return entry.startsWith("-");
	}

	/** Every readout the HUD can draw. Order matches {@link #defaultOrder()}. */
	public static final List<String> ELEMENTS =
			List.of("location", "time", "date", "session",
					"purse", "coinshour", "coinsgained", "bits", "bitsgained",
					"health", "defense", "mana",
					"slayer", "objective", "events", "commissions", "garden", "pet",
					"bank", "skill", "composter", "visitors",
					"gardenlevel", "fortune", "pests",
					"milestone", "croprate", "align",
					"speed", "gems", "skillrate", "drops", "fishing",
					"museum", "jacob", "cooldown",
					"powder", "nucleus", "gemstones",
					"dungeon", "party", "other");

	/**
	 * The standard layout as it ships, hand-designed:
	 * where-and-when, the money block, quests, then passthrough - each divided.
	 * The gain deltas, own-stat lines and party info start removed (markers),
	 * one Add away for whoever wants them.
	 */
	private static final List<String> DEFAULT_ORDER =
			List.of("location", "time", "date", "session", DIVIDER,
					"purse", "coinshour", "bits", DIVIDER,
					"slayer", "objective", DIVIDER,
					"events", "other",
					"-coinsgained", "-bitsgained",
					"-health", "-defense", "-mana", "-dungeon", "-party",
					"-commissions", "-garden", "-pet",
					"-bank", "-skill", "-composter", "-visitors",
					"-gardenlevel", "-fortune", "-pests",
					"-milestone", "-croprate", "-align",
					"-museum", "-jacob", "-cooldown",
					"-powder", "-nucleus", "-gemstones",
					"-speed", "-gems", "-skillrate", "-drops", "-fishing");

	/**
	 * The Catacombs profile as it ships, also hand-designed: session and coins
	 * up top, the dungeon block (Other lines) in the middle, the party at the
	 * bottom, everything else removed - the map and action bar cover the rest.
	 */
	private static final List<String> CATACOMBS_ORDER =
			List.of("session", DIVIDER,
					"purse", "coinshour", DIVIDER,
					"dungeon", "other", DIVIDER,
					"party",
					"-location", "-time", "-date", "-coinsgained", "-bits", "-bitsgained",
					"-health", "-defense", "-mana",
					"-slayer", "-objective", "-events", "-commissions", "-garden", "-pet",
					"-bank", "-skill", "-composter", "-visitors",
					"-gardenlevel", "-fortune", "-pests",
					"-milestone", "-croprate", "-align",
					"-museum", "-jacob", "-cooldown",
					"-powder", "-nucleus", "-gemstones",
					"-speed", "-gems", "-skillrate", "-drops", "-fishing");

	/**
	 * The Garden profile leads with the per-line garden modules in the
	 * order the old combined readout drew them, so splitting the element
	 * changed what can be arranged, not what the profile shows.
	 */
	private static final List<String> GARDEN_ORDER =
			List.of("garden", "gardenlevel", "milestone", "fortune", "pests",
					"visitors", "croprate", "align", DIVIDER,
					"session", DIVIDER,
					"purse", "coinshour", DIVIDER,
					"other",
					"-location", "-time", "-date", "-coinsgained", "-bits", "-bitsgained",
					"-health", "-defense", "-mana", "-dungeon", "-party",
					"-slayer", "-objective", "-events", "-commissions", "-pet",
					"-bank", "-skill", "-composter",
					"-museum", "-jacob", "-cooldown",
					"-powder", "-nucleus", "-gemstones",
					"-speed", "-gems", "-skillrate", "-drops", "-fishing");

	private HudLayout() {
	}

	public static List<String> defaultOrder() {
		return DEFAULT_ORDER;
	}

	public static List<String> catacombsOrder() {
		return CATACOMBS_ORDER;
	}

	/**
	 * The Garden profile as it ships: the garden readout leads (widgets,
	 * crop rate, row alignment), then session and coins, then passthrough -
	 * everything else removed. Built-in and locked, like the Catacombs one.
	 */
	public static List<String> gardenOrder() {
		return GARDEN_ORDER;
	}

	/**
	 * Cleans up a saved order: drops entries that are no longer known, and
	 * gathers removal markers at the end where the arrange screen expects them.
	 *
	 * <p>An element the saved order predates joins as a removal marker, not a
	 * visible row - by the design rule, new readouts appear in the Add menu and
	 * never insert themselves into a layout somebody already arranged.
	 */
	public static List<String> sanitise(List<String> configured) {
		if (configured == null || configured.isEmpty()) {
			return defaultOrder();
		}

		List<String> out = new ArrayList<>();
		List<String> markers = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();

		for (String id : configured) {
			if (DIVIDER.equals(id)) {
				out.add(DIVIDER);
				continue;
			}

			if (isRemoval(id)) {
				String removed = id.substring(1);

				if (ELEMENTS.contains(removed) && seen.add(removed)) {
					markers.add(id);
				}

				continue;
			}

			// Unknown ids are dropped rather than kept: they are elements removed in a
			// later version, and passing them on would draw nothing but still count as
			// content when placing dividers.
			if (ELEMENTS.contains(id) && seen.add(id)) {
				out.add(id);
			}
		}

		for (String id : ELEMENTS) {
			if (!seen.contains(id)) {
				markers.add(removalMarker(id));
			}
		}

		out.addAll(markers);
		return out;
	}

	/**
	 * The final order to draw, given which entries actually produced content.
	 *
	 * <p>Elements with nothing to show are dropped, then dividers are reduced to
	 * those that still sit between two visible things - a run of them collapses to
	 * one, and any left at either end is removed.
	 *
	 * @param order        the sanitised order
	 * @param withContent  ids that produced at least one line this frame
	 */
	public static List<String> normalise(List<String> order, Set<String> withContent) {
		List<String> kept = new ArrayList<>();

		for (String id : order) {
			if (DIVIDER.equals(id) || withContent.contains(id)) {
				kept.add(id);
			}
		}

		List<String> out = new ArrayList<>(kept.size());
		boolean pendingDivider = false;

		for (String id : kept) {
			if (DIVIDER.equals(id)) {
				// Only remember it; whether it earns a place depends on something
				// real following it.
				pendingDivider = !out.isEmpty();
				continue;
			}

			if (pendingDivider) {
				out.add(DIVIDER);
				pendingDivider = false;
			}

			out.add(id);
		}

		return out;
	}
}
