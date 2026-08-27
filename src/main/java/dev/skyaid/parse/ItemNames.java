package dev.skyaid.parse;

import java.util.Locale;
import java.util.Set;

/**
 * Cleans a Skyblock item's display name into something a market search
 * accepts: no star glyphs, no pet level tag, no reforge prefix - just the
 * base name. "Fierce Superior Dragon Helmet" with three stars becomes
 * "Superior Dragon Helmet".
 */
public final class ItemNames {
	/**
	 * The common reforge prefixes. Dropped only when a word follows them, so
	 * an item actually NAMED like a reforge keeps its name. The list is not
	 * exhaustive - the search is prefilled, never sent, so a missed one just
	 * means one word to delete by hand.
	 */
	private static final Set<String> REFORGES = Set.of(
			"sharp", "spicy", "fabled", "withered", "heroic", "fierce", "wise",
			"pure", "necrotic", "ancient", "renowned", "giant", "jaded",
			"auspicious", "fleet", "precise", "rapid", "unreal", "deadly",
			"fine", "grand", "hasty", "neat", "rich", "awkward", "clean",
			"gentle", "odd", "fast", "smart", "titanic", "loving", "ridiculous",
			"bustling", "mossy", "festive", "gilded", "cubic", "warped",
			"reinforced", "salty", "treacherous", "lucky", "stiff", "dirty",
			"chomp", "pitchin", "submerged", "shaded", "strengthened",
			"glistening", "waxed", "candied", "perfect", "spiked", "hyper",
			"coldfused", "blessed", "toil", "bountiful", "heated", "magnetic",
			"fruitful", "blooming", "rooted", "royal", "suspicious", "snowy",
			"blood-soaked");

	private ItemNames() {
	}

	public static String cleanForSearch(String displayName) {
		if (displayName == null) {
			return "";
		}

		// Some containers carry legacy colour codes INSIDE the raw text.
		// Without this the char filter below drops the section sign but
		// keeps its digit - "§6Name" searched as "6Name".
		String name = FormatCodes.strip(displayName);

		// Pet level tags lead the name: "[Lvl 100] Ender Dragon".
		if (name.startsWith("[")) {
			int close = name.indexOf(']');

			if (close > 0) {
				name = name.substring(close + 1);
			}
		}

		// Keep the characters names are made of; stars and glyphs vanish.
		StringBuilder kept = new StringBuilder(name.length());

		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);

			if (Character.isLetterOrDigit(c) || c == ' ' || c == '\'' || c == '-') {
				kept.append(c);
			}
		}

		name = kept.toString().trim().replaceAll(" +", " ");

		// A leading reforge goes, but never the whole name.
		int space = name.indexOf(' ');

		if (space > 0 && REFORGES.contains(
				name.substring(0, space).toLowerCase(Locale.ROOT))) {
			name = name.substring(space + 1);
		}

		return name;
	}
}
