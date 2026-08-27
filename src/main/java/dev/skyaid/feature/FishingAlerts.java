package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.parse.FishingLines;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * A calm nudge when a notable sea creature spawns: the announcement line is
 * easy to miss in busy chat, so the catch also lands on the action bar with a
 * low ping. Double hooks get just the ping - the catch message is already on
 * screen.
 *
 * <p>Display and sound only, driven entirely by chat Hypixel already sent.
 */
public final class FishingAlerts {
	/** Session tally for the fishing HUD element. */
	private static int sessionCreatures;
	private static String lastCreatureName = "";
	private static long lastCreatureAt;

	public static int sessionCreatures() {
		return sessionCreatures;
	}

	/** "Grim Reaper (4m ago)". */
	public static java.util.Optional<String> lastCreature() {
		if (sessionCreatures == 0) {
			return java.util.Optional.empty();
		}

		return java.util.Optional.of(lastCreatureName + " ("
				+ dev.skyaid.core.DropTracker.ago(lastCreatureAt) + ")");
	}

	private FishingAlerts() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !ConfigManager.get().enabled
					|| !ConfigManager.get().chat.fishingAlerts
					|| !HypixelDetector.isOnHypixel()) {
				return;
			}

			String text = message.getString();

			FishingLines.creature(text).ifPresent(creature -> {
				sessionCreatures++;
				lastCreatureName = creature;
				lastCreatureAt = System.currentTimeMillis();

				Minecraft client = Minecraft.getInstance();

				if (client.gui != null) {
					client.gui.hud.setOverlayMessage(
							Component.literal(creature + "!")
									.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
							false);
				}

				// Deeper than the mention ping, so the ear learns the difference.
				ping(0.7f);
				dev.skyaid.core.LifetimeStats.countSeaCreature();
			});

			if (FishingLines.isDoubleHook(text)) {
				ping(1.2f);
			}
		});
	}

	private static void ping(float pitch) {
		Minecraft.getInstance().getSoundManager().playDelayed(
				SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, pitch), 0);
	}
}
