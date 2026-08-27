package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.core.TabListReader;
import dev.skyaid.parse.FormatCodes;
import dev.skyaid.parse.GardenLines;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Watches the Garden composter's tab widget and says something ONCE when
 * fuel or organic matter runs low - the composter stopping silently is how
 * compost income quietly dies.
 *
 * <p>The tab wordings ("Fuel: 58.4k", "Organic Matter: 12k") are ecosystem
 * knowledge; the dump prints the tab list, which is how a mismatch gets
 * corrected. Wrong wording means no alert, nothing worse.
 */
public final class ComposterAlert {
	/** Alert when a resource drops below this many units. */
	private static final long LOW = 20_000;

	/** Re-arm only after a refill clears this, so one low spell = one line. */
	private static final long REARM = 40_000;

	private static final int CHECK_INTERVAL_TICKS = 100;

	private static int tickCounter;
	private static boolean fuelAlerted;
	private static boolean matterAlerted;

	private ComposterAlert() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ConfigManager.get().enabled || !ConfigManager.get().composterAlert
					|| client.player == null || !HypixelDetector.isOnHypixel()
					|| !SkyblockTracker.state().inSkyblock()) {
				return;
			}

			if (++tickCounter < CHECK_INTERVAL_TICKS) {
				return;
			}

			tickCounter = 0;

			long fuel = -1;
			long matter = -1;

			for (String raw : TabListReader.lines()) {
				String line = GardenLines.stripIcons(
						FormatCodes.strip(raw)).trim();

				if (line.startsWith("Fuel:")) {
					fuel = parseShorthand(line.substring(5).trim());
				} else if (line.startsWith("Organic Matter:")) {
					matter = parseShorthand(line.substring(15).trim());
				}
			}

			fuelAlerted = check("Composter fuel", fuel, fuelAlerted);
			matterAlerted = check("Composter organic matter", matter, matterAlerted);
		});
	}

	/** One alert per low spell; refilled past the re-arm point resets it. */
	private static boolean check(String what, long amount, boolean alerted) {
		if (amount < 0) {
			return alerted; // Widget not visible; keep the current state.
		}

		if (!alerted && amount < LOW) {
			say(what + " is low: " + shorthand(amount)
					+ " left - the composter stops without it.");
			return true;
		}

		if (alerted && amount >= REARM) {
			return false;
		}

		return alerted;
	}

	/** "58.4k" / "1.2M" / "980" -> units; -1 when it does not parse. */
	static long parseShorthand(String text) {
		String cleaned = text.replace(",", "").trim();

		if (cleaned.isEmpty()) {
			return -1;
		}

		double scale = 1;
		char last = Character.toLowerCase(cleaned.charAt(cleaned.length() - 1));

		if (last == 'k') {
			scale = 1_000;
			cleaned = cleaned.substring(0, cleaned.length() - 1);
		} else if (last == 'm') {
			scale = 1_000_000;
			cleaned = cleaned.substring(0, cleaned.length() - 1);
		}

		try {
			return Math.round(Double.parseDouble(cleaned) * scale);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String shorthand(long amount) {
		return amount >= 1_000_000
				? String.format(Locale.ROOT, "%.1fM", amount / 1_000_000.0)
				: amount >= 1_000
						? String.format(Locale.ROOT, "%.1fk", amount / 1_000.0)
						: Long.toString(amount);
	}

	private static void say(String message) {
		var client = Minecraft.getInstance();

		if (client.gui != null) {
			var chat = client.gui.hud.getChat();
			chat.addClientSystemMessage(Component.empty());
			chat.addClientSystemMessage(Component.literal(message)
					.withStyle(ChatFormatting.RED));
			chat.addClientSystemMessage(Component.empty());
		}
	}
}
