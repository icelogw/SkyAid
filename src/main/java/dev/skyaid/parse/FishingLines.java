package dev.skyaid.parse;

import java.util.Map;
import java.util.Optional;

/**
 * Recognises the chat announcements of notable sea creatures - each rare
 * catch has one fixed flavour line. Only the creatures worth an alert are
 * listed; common catches stay quiet by design.
 *
 * <p>Wording is ecosystem knowledge, not yet captures: any line that turns
 * out to differ in game should be dumped from chat and corrected here, the
 * same way the sidebar fixtures grew.
 */
public final class FishingLines {
	private static final Map<String, String> CREATURES = Map.ofEntries(
			Map.entry("You hear a massive rumble as Thunder emerges.", "Thunder"),
			Map.entry("You have angered a legendary creature... Lord Jawbus has arrived.",
					"Lord Jawbus"),
			Map.entry("The Sea Emperor arises from the depths.", "Sea Emperor"),
			Map.entry("What is this creature!?", "Yeti"),
			Map.entry("Hide no longer, a Great White Shark has tracked your scent "
					+ "and thirsts for your blood!", "Great White Shark"),
			Map.entry("The Water Hydra has come to test your strength.", "Water Hydra"),
			Map.entry("The spirit of a long lost Phantom Fisher has come to haunt you.",
					"Phantom Fisher"),
			Map.entry("This can't be! The manifestation of death himself!",
					"Grim Reaper"),
			Map.entry("The Carrot King is infuriated!", "Carrot King"));

	private FishingLines() {
	}

	/** The creature a chat line announces, for the ones worth announcing. */
	public static Optional<String> creature(String line) {
		return Optional.ofNullable(CREATURES.get(line.trim()));
	}

	/** "It's a Double Hook!" rides along with the catch message. */
	public static boolean isDoubleHook(String line) {
		return line.contains("Double Hook");
	}
}
