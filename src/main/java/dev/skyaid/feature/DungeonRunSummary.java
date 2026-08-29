package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.core.TabListReader;
import dev.skyaid.parse.FormatCodes;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * One padded chat block when a dungeon run ends: how long it took, how many
 * deaths the party ate, and the secrets count off the tab list - the recap
 * Hypixel's own EXTRA STATS screen scrolls straight past.
 *
 * <p>The run-end trigger ("EXTRA STATS") and the death skull are Hypixel
 * wordings, unverified ecosystem knowledge: a miss means no summary line,
 * nothing worse.
 */
public final class DungeonRunSummary {
	private static long runStartMillis;
	private static int deaths;
	private static boolean inRun;
	private static boolean reported;

	private DungeonRunSummary() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean inCatacombs = SkyblockTracker.state().inCatacombs();

			if (inCatacombs && !inRun) {
				inRun = true;
				reported = false;
				deaths = 0;
				runStartMillis = System.currentTimeMillis();
			} else if (!inCatacombs && inRun) {
				inRun = false;
			}
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !inRun || !ConfigManager.get().enabled
					|| !HypixelDetector.isOnHypixel()) {
				return;
			}

			String text = FormatCodes.strip(message.getString()).trim();

			// "☠ G00PED died and became a ghost." - one skull per death.
			if (text.startsWith("☠")) {
				deaths++;
				return;
			}

			if (!reported && text.contains("EXTRA STATS")) {
				dev.skyaid.core.EventLog.event("dungeon", "run ended: deaths " + deaths);
				reported = true;
				summarise();
			}
		});
	}

	private static void summarise() {
		long elapsed = System.currentTimeMillis() - runStartMillis;
		String secrets = null;

		for (String raw : TabListReader.lines()) {
			String line = FormatCodes.strip(raw).trim();

			if (line.startsWith("Secrets Found:")) {
				secrets = line.substring(14).trim();
				break;
			}
		}

		var message = Component.literal("Run summary: ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(String.format(Locale.ROOT, "%d:%02d",
								elapsed / 60_000, elapsed / 1000 % 60))
						.withStyle(ChatFormatting.WHITE))
				.append(Component.literal("  deaths ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(Integer.toString(deaths))
						.withStyle(deaths == 0
								? ChatFormatting.GREEN : ChatFormatting.RED));

		if (secrets != null) {
			message = message
					.append(Component.literal("  secrets ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(secrets).withStyle(ChatFormatting.GOLD));
		}

		var client = Minecraft.getInstance();

		if (client.gui != null) {
			var chat = client.gui.hud.getChat();
			chat.addClientSystemMessage(Component.empty());
			chat.addClientSystemMessage(message);
			chat.addClientSystemMessage(Component.empty());
		}
	}
}
