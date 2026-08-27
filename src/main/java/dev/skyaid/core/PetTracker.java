package dev.skyaid.core;

import dev.skyaid.parse.FormatCodes;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers which pet is out, from the summon confirmations in chat, for the
 * HUD's "pet" element. Purely observational - Hypixel does not put the active
 * pet anywhere the client can read directly.
 *
 * <p>All three wordings are ecosystem knowledge, NOT verified against
 * captures: if the line ever changes shape, the element just goes quiet, and
 * a chat screenshot is what fixes it.
 */
public final class PetTracker {
	private static final Pattern SUMMONED =
			Pattern.compile("^You summoned your (.+)!$");
	private static final Pattern DESPAWNED =
			Pattern.compile("^You despawned your (.+)!$");
	private static final Pattern AUTOPET =
			Pattern.compile("^Autopet equipped your (.+?)! VIEW RULE$");

	private static volatile String currentPet;

	private PetTracker() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !HypixelDetector.isOnHypixel()) {
				return;
			}

			String text = FormatCodes.strip(message.getString()).trim();

			Matcher summoned = SUMMONED.matcher(text);

			if (summoned.matches()) {
				currentPet = summoned.group(1);
				return;
			}

			Matcher autopet = AUTOPET.matcher(text);

			if (autopet.matches()) {
				currentPet = autopet.group(1);
				return;
			}

			if (DESPAWNED.matcher(text).matches()) {
				currentPet = null;
			}
		});
	}

	/** The pet last seen summoned, e.g. "[Lvl 100] Ender Dragon". */
	public static Optional<String> current() {
		return Optional.ofNullable(currentPet);
	}
}
