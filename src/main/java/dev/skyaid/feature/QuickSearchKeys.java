package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * F1 over an item opens the auction-house search for it; F2 the bazaar. Each
 * press sends exactly ONE command with the item's cleaned name - the same
 * thing typing "/ahs x" and pressing enter does, and the single-command-per-
 * keypress shape Hypixel's rules explicitly allow (vanilla command macros).
 * The prefill was amended-only rule for these two keys specifically;
 * everything else in the mod still never acts.
 */
public final class QuickSearchKeys {
	private static final int KEY_F1 = 290;
	private static final int KEY_F2 = 291;

	/** F4 opens the bazaar itself - vanilla binds nothing to F4. */
	private static final int KEY_F4 = 293;

	private QuickSearchKeys() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?>)) {
				return;
			}

			ScreenKeyboardEvents.allowKeyPress(screen).register((s, key) -> {
				if (!ConfigManager.get().enabled || !HypixelDetector.isOnHypixel()) {
					return true;
				}

				// F4 over an item searches the bazaar FOR it;
				// over nothing it opens the bazaar itself. The in-world press
				// goes through the rebindable Keybinds entry; this covers
				// presses while a menu is open.
				if (key.key() == KEY_F4) {
					String hovered = MuseumTracker.overlayHoveredName();

					if (hovered == null) {
						hovered = PriceTooltips.hoveredItemName();
					}

					if (hovered != null && !hovered.isBlank()) {
						openSearch(false, hovered);
						return false;
					}

					Minecraft.getInstance().execute(() -> {
						var connection = Minecraft.getInstance().getConnection();

						if (connection != null) {
							connection.sendCommand("bz");
						}
					});
					return false;
				}

				if (key.key() != KEY_F1 && key.key() != KEY_F2) {
					return true;
				}

				// The museum overlay's hovered tile outranks the last real
				// tooltip: its grid draws on top of the container, and its
				// names are the exact harvested display names.
				String name = MuseumTracker.overlayHoveredName();

				if (name == null) {
					name = PriceTooltips.hoveredItemName();
				}

				if (name == null || name.isBlank()) {
					return true;
				}

				openSearch(key.key() == KEY_F1, name);
				return false;
			});
		});
	}

	/** Sends the one search command, opening the market at the item. */
	static void openSearch(boolean auctionHouse, String itemName) {
		String cleaned = dev.skyaid.parse.ItemNames.cleanForSearch(itemName);

		if (cleaned.isBlank()) {
			return;
		}

		String command = (auctionHouse ? "ahs " : "bz ") + cleaned;
		Minecraft.getInstance().execute(() -> {
			var connection = Minecraft.getInstance().getConnection();

			if (connection != null) {
				connection.sendCommand(command);
			}
		});
	}
}
