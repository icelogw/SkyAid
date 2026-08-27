package dev.skyaid.parse;

import java.util.List;
import java.util.Optional;

/**
 * Three Weirdos: the phrases that give the truthful NPC away. Any NPC line
 * containing one of these marks its speaker as the one whose chest holds the
 * blessing. Phrase list from the Skytils data repository; an unmatched line
 * simply says nothing.
 */
public final class WeirdosPhrases {
	private static final List<String> SOLUTIONS = List.of(
			"The reward is not in my chest!",
			"At least one of them is lying, and the reward is not in",
			"My chest doesn't have the reward. We are all telling the truth",
			"My chest has the reward and I'm telling the truth",
			"The reward isn't in any of our chests",
			"Both of them are telling the truth.");

	private WeirdosPhrases() {
	}

	/**
	 * The truthful NPC's name, when a stripped chat line is one of the known
	 * giveaway phrases. Lines look like "[NPC] Baxter: My chest has ...".
	 */
	public static Optional<String> truthfulNpc(String stripped) {
		if (!stripped.startsWith("[NPC] ")) {
			return Optional.empty();
		}

		int colon = stripped.indexOf(':');

		if (colon <= 6) {
			return Optional.empty();
		}

		for (String phrase : SOLUTIONS) {
			if (stripped.contains(phrase)) {
				return Optional.of(stripped.substring(6, colon).trim());
			}
		}

		return Optional.empty();
	}
}
