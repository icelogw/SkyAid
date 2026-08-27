package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers the last "ability is on cooldown for Xs" message and counts it
 * down as a HUD line, so the wait is a number instead of repeated clicking.
 *
 * <p>Read-only: the message wording is Hypixel's ("This ability is on
 * cooldown for 4s.", chat or action bar), unverified ecosystem knowledge -
 * a miss means no line.
 */
public final class CooldownTracker {
	private static final Pattern COOLDOWN = Pattern.compile(
			".*ability is (?:currently )?on cooldown for (\\d+)s.*");

	private static volatile long readyAtMillis;

	private CooldownTracker() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!ConfigManager.get().enabled || !HypixelDetector.isOnHypixel()) {
				return;
			}

			Matcher match = COOLDOWN.matcher(
					dev.skyaid.parse.FormatCodes.strip(message.getString()).trim());

			if (match.matches()) {
				readyAtMillis = System.currentTimeMillis()
						+ Long.parseLong(match.group(1)) * 1000;
			}
		});
	}

	/** "Cooldown: 12s" while one is running, else empty. */
	public static Optional<Component> hudLine() {
		long remaining = readyAtMillis - System.currentTimeMillis();

		if (remaining <= 0) {
			return Optional.empty();
		}

		return Optional.of(Component.literal("Cooldown: ")
				.withStyle(net.minecraft.ChatFormatting.GRAY)
				.append(Component.literal((remaining + 999) / 1000 + "s")
						.withStyle(net.minecraft.ChatFormatting.RED)));
	}
}
