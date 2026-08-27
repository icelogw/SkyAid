package dev.skyaid.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import dev.skyaid.SkyAidClient;
import dev.skyaid.config.Config;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.config.ConfigScreen;
import dev.skyaid.core.SkyblockTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Every key binding the mod registers, in one place.
 *
 * <p>Two rules hold for all of them, and must keep holding:
 * <ul>
 *   <li>A binding may open a screen, toggle a setting, or prefill the chat box.
 *       It may never send a command, click, or otherwise act for the player -
 *       that would be automation, which Hypixel bans outright.</li>
 *   <li>Every binding defaults to unbound, so installing the mod never steals a
 *       key the player already uses.</li>
 * </ul>
 *
 * <p>Registering through {@link KeyMappingHelper} puts these in the vanilla
 * Options - Controls screen under their own category, which is where players
 * expect to rebind keys - so the mod needs no rebinding UI of its own.
 */
public final class Keybinds {
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(
					Identifier.fromNamespaceAndPath(SkyAidClient.MOD_ID, "main"));

	private static KeyMapping toggleHud;
	private static KeyMapping toggleMod;
	private static KeyMapping openSettings;
	private static KeyMapping shareLocation;
	private static KeyMapping openBazaar;
	private static KeyMapping[] mouseLockKeys;
	private static KeyMapping mouseLockEnable;

	private Keybinds() {
	}

	public static void register() {
		toggleHud = register("key.skyaid.toggle_hud");
		toggleMod = register("key.skyaid.toggle_mod");
		openSettings = register("key.skyaid.open_settings");
		shareLocation = register("key.skyaid.share_location");

		// The one binding with a DEFAULT key and the one that sends a
		// command, both deliberate: F4 (vanilla binds
		// nothing there) opens the bazaar, one command per press - the same
		// amendment that covers the F1/F2 market keys.
		openBazaar = register("key.skyaid.open_bazaar",
				InputConstants.KEY_F4);

		// The mouse-lock preset keys: one deterministic camera set per
		// press, - see MouseLock.
		mouseLockKeys = new KeyMapping[6];

		for (int slot = 1; slot <= mouseLockKeys.length; slot++) {
			mouseLockKeys[slot - 1] = register("key.skyaid.mouse_lock_" + slot);
		}

		// The master switch on a key: same as /skyaid mouselock on / off.
		mouseLockEnable = register("key.skyaid.mouse_lock_enable");

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Config config = ConfigManager.get();

			// consumeClick() drains one queued press, so a held key fires once.
			while (toggleHud.consumeClick()) {
				config.skyblockHud.visible = !config.skyblockHud.visible;
				ConfigManager.save();
				notify(client, "Skyblock HUD", config.skyblockHud.visible);
			}

			while (toggleMod.consumeClick()) {
				config.enabled = !config.enabled;
				ConfigManager.save();
				notify(client, "SkyAid", config.enabled);
			}

			while (openSettings.consumeClick()) {
				// Opening a screen is the only thing this does - see the class note.
				// In-game the current screen is null, so closing returns to the game.
				client.setScreenAndShow(new ConfigScreen(client.gui.screen()));
			}

			while (shareLocation.consumeClick()) {
				// Prefills the chat box and stops there - the class note's third
				// permitted action. Nothing is sent; the player reads, edits, and
				// decides.
				if (client.player != null) {
					client.setScreenAndShow(new ChatScreen(locationCallout(client), false));
				}
			}

			// Hold-to-lock, a deliberate design: the tripod exists
			// only while a key is physically held, so it cannot be left on
			// for an unattended setup - releasing always frees the camera.
			boolean[] held = new boolean[mouseLockKeys.length];

			for (int i = 0; i < mouseLockKeys.length; i++) {
				while (mouseLockKeys[i].consumeClick()) {
					// Drained so stale clicks never pile up; state drives it.
				}

				held[i] = mouseLockKeys[i].isDown();
			}

			dev.skyaid.feature.MouseLock.holdTick(held);

			while (mouseLockEnable.consumeClick()) {
				dev.skyaid.feature.MouseLock.toggleEnabled();
			}

			while (openBazaar.consumeClick()) {
				if (config.enabled && client.player != null
						&& dev.skyaid.core.HypixelDetector.isOnHypixel()
						&& SkyblockTracker.state().inSkyblock()) {
					var connection = client.getConnection();

					if (connection != null) {
						connection.sendCommand("bz");
					}
				}
			}
		});
	}

	private static KeyMapping register(String translationKey) {
		return register(translationKey, InputConstants.UNKNOWN.getValue());
	}

	private static KeyMapping register(String translationKey, int defaultKey) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(
				translationKey,
				InputConstants.Type.KEYSYM,
				defaultKey,
				CATEGORY));
	}

	/** "x: 123, y: 70, z: -45 (Village)" - a party callout, ready to send or edit. */
	private static String locationCallout(Minecraft client) {
		String zone = SkyblockTracker.state().location()
				.map(name -> " (" + name + ")")
				.orElse("");

		return "x: " + client.player.getBlockX()
				+ ", y: " + client.player.getBlockY()
				+ ", z: " + client.player.getBlockZ()
				+ zone;
	}

	/** Feedback goes to the action bar so it does not clutter chat history. */
	private static void notify(Minecraft client, String what, boolean on) {
		if (client.player == null) {
			return;
		}

		client.gui.hud.setOverlayMessage(
				Component.literal(what + (on ? ": on" : ": off")), false);
	}
}
