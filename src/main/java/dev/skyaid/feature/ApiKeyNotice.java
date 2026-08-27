package dev.skyaid.feature;

import dev.skyaid.api.HypixelApiClient;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;

/**
 * Tells the player once that an API key is optional but unlocks stats lookup.
 *
 * <p>A key is genuinely optional: the HUD, chat cleanup and the keyless Hypixel
 * endpoints (the bazaar, for one) all work without one. Only
 * {@code /skyaid stats} needs it. So this is worded as a recommendation, not a
 * warning, and it never nags - once dismissed it is recorded in the config and
 * never shown again.
 *
 * <p>On Hypixel's key policy: a personal key is for personal use. Hypixel's API
 * policy forbids entering your key into a <em>publicly available</em> mod, so if
 * this mod is ever distributed, this prompt must go and be replaced with
 * something that does not ask users for their key.
 */
public final class ApiKeyNotice {
	/** Wait a few seconds after joining so the notice is not buried by lobby spam. */
	private static final int DELAY_TICKS = 20 * 5;

	private static final String DASHBOARD_URL = "https://developer.hypixel.net/dashboard";

	private static int ticksOnHypixel;
	private static boolean shownThisSession;

	private ApiKeyNotice() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!HypixelDetector.isOnHypixel()) {
				// Reset so the countdown restarts on the next join.
				ticksOnHypixel = 0;
				return;
			}

			if (shownThisSession
					|| ConfigManager.get().apiKeyNoticeDismissed
					|| HypixelApiClient.hasApiKey()) {
				return;
			}

			if (++ticksOnHypixel < DELAY_TICKS || client.player == null) {
				return;
			}

			show(client);
		});
	}

	private static void show(Minecraft client) {
		shownThisSession = true;
		ConfigManager.get().apiKeyNoticeDismissed = true;
		ConfigManager.save();

		// addClientSystemMessage marks these as locally generated, so they are never
		// mistaken for something the server sent.
		var chat = client.gui.hud.getChat();
		chat.addClientSystemMessage(Component.empty());
		chat.addClientSystemMessage(header());
		chat.addClientSystemMessage(body());
		chat.addClientSystemMessage(link());
		chat.addClientSystemMessage(Component.empty());
	}

	private static Component header() {
		return Component.literal("[SkyAid] ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal("A Hypixel API key is recommended, but optional.")
						.withStyle(ChatFormatting.WHITE));
	}

	private static Component body() {
		return Component.literal("  The HUD and chat features work without one. ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal("/skyaid stats")
						.withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(" needs a key.")
						.withStyle(ChatFormatting.GRAY));
	}

	private static Component link() {
		return Component.literal("  Get one at ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(DASHBOARD_URL)
						.withStyle(style -> style
								.withColor(ChatFormatting.AQUA)
								.withUnderlined(true)
								.withClickEvent(new ClickEvent.OpenUrl(URI.create(DASHBOARD_URL)))))
				.append(Component.literal(", then run ").withStyle(ChatFormatting.GRAY))
				// RunCommand on a client command opens the popup without the key ever
				// being typed into chat. Clicking is the safe path, so it is the one
				// offered here.
				.append(Component.literal("/skyaid key add")
						.withStyle(style -> style
								.withColor(ChatFormatting.YELLOW)
								.withUnderlined(true)
								.withClickEvent(new ClickEvent.RunCommand("/skyaid key add"))))
				.append(Component.literal(" to paste it in.").withStyle(ChatFormatting.GRAY));
	}
}
