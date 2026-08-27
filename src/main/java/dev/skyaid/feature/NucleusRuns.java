package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Counts Crystal Nucleus completions - the chat line naming the Nucleus and
 * a completion - into a session HUD line with a runs-per-hour rate, and the
 * lifetime ledger.
 *
 * <p>The completion wording is unverified ecosystem knowledge; a miss means
 * the count stays where it was.
 */
public final class NucleusRuns {
	private static int sessionRuns;
	private static long firstRunAt;

	private NucleusRuns() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !ConfigManager.get().enabled
					|| !HypixelDetector.isOnHypixel()) {
				return;
			}

			String text = dev.skyaid.parse.FormatCodes.strip(
					message.getString()).toLowerCase(Locale.ROOT);

			if (text.contains("crystal nucleus") && text.contains("complet")) {
				sessionRuns++;

				if (firstRunAt == 0) {
					firstRunAt = System.currentTimeMillis();
				}

				dev.skyaid.core.LifetimeStats.countNucleusRun();
			}
		});
	}

	/** "Nucleus: 3 this session (1.2/hr)" once a run has landed. */
	public static Optional<Component> hudLine() {
		if (sessionRuns == 0) {
			return Optional.empty();
		}

		double hours = (System.currentTimeMillis() - firstRunAt) / 3_600_000.0;
		String rate = sessionRuns >= 2 && hours > 0.05
				? String.format(Locale.ROOT, "  (%.1f/hr)", sessionRuns / hours)
				: "";

		return Optional.of(Component.literal("Nucleus: ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(sessionRuns + " this session")
						.withStyle(ChatFormatting.LIGHT_PURPLE))
				.append(Component.literal(rate).withStyle(ChatFormatting.GRAY)));
	}
}
