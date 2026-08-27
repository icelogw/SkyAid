package dev.skyaid.parse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Matching what a player typed against Hypixel's bazaar product ids. Ids look
 * like {@code ENCHANTED_DIAMOND} or {@code BOOSTER_COOKIE}; people type
 * "enchanted diamond". Pure string work, testable offline - the network side
 * lives in the feature layer.
 */
public final class Bazaar {
	private Bazaar() {
	}

	/** "Enchanted diamond " -> "ENCHANTED_DIAMOND", the id spelling. */
	public static String normalise(String query) {
		return query.trim().toUpperCase(Locale.ROOT)
				.replaceAll("[^A-Z0-9]+", "_")
				.replaceAll("^_+|_+$", "");
	}

	/** An id rendered back for humans: "ENCHANTED_DIAMOND" -> "Enchanted Diamond". */
	public static String pretty(String productId) {
		StringBuilder out = new StringBuilder(productId.length());

		for (String word : productId.split("_")) {
			if (word.isEmpty()) {
				continue;
			}

			if (!out.isEmpty()) {
				out.append(' ');
			}

			out.append(word.charAt(0)).append(
					word.substring(1).toLowerCase(Locale.ROOT));
		}

		return out.toString();
	}

	/**
	 * The name Hypixel itself sells an id under - what /bz must be given.
	 * Mostly {@link #pretty}, except the families whose ids are word-reversed
	 * or prefixed: ESSENCE_DRAGON is sold as "Dragon Essence", and
	 * enchantment ids carry an ENCHANTMENT_ prefix their books do not.
	 */
	public static String displayName(String productId) {
		if (productId.startsWith("ESSENCE_")) {
			return pretty(productId.substring("ESSENCE_".length())) + " Essence";
		}

		String name = pretty(productId);
		return name.startsWith("Enchantment ")
				? name.substring("Enchantment ".length()) : name;
	}

	/**
	 * The product ids matching a query, best first: an exact id match wins
	 * outright, otherwise every id containing the query as a substring. One
	 * result means confidence; several mean the caller should ask, not guess.
	 */
	public static List<String> match(Collection<String> productIds, String query) {
		String wanted = normalise(query);

		if (wanted.isEmpty()) {
			return List.of();
		}

		if (productIds.contains(wanted)) {
			return List.of(wanted);
		}

		List<String> contains = new ArrayList<>();

		for (String id : productIds) {
			if (id.contains(wanted)
					|| normalise(displayName(id)).contains(wanted)) {
				contains.add(id);
			}
		}

		// Shorter ids first: for "diamond", plain ENCHANTED_DIAMOND should
		// outrank ENCHANTED_DIAMOND_BLOCK in the suggestion list.
		contains.sort(java.util.Comparator
				.comparingInt(String::length).thenComparing(id -> id));
		return contains;
	}
}
