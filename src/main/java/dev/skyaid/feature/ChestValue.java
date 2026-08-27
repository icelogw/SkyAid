package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.parse.Numbers;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.OptionalLong;

/**
 * Values a dungeon reward chest the moment it opens: one chat line summing
 * what its loot would sell for, bazaar and lowest-BIN prices combined - the
 * "is this chest worth buying" question answered while the menu is still up.
 *
 * <p>Items without a known price are counted as zero and listed, so a big
 * unpriced drop is never silently valued at nothing.
 */
public final class ChestValue {
	private static final List<String> CHEST_TITLES = List.of(
			"Wooden Chest", "Gold Chest", "Diamond Chest",
			"Emerald Chest", "Obsidian Chest", "Bedrock Chest");

	/** The screen instance already reported, so each chest speaks once. */
	private static Object reportedScreen;

	private ChestValue() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ConfigManager.get().enabled || !ConfigManager.get().chestValue
					|| !HypixelDetector.isOnHypixel()) {
				return;
			}

			// 26.2 moved the active screen from Minecraft onto Gui.
			if (client.gui == null
					|| !(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
				reportedScreen = null;
				return;
			}

			if (screen == reportedScreen) {
				return;
			}

			String title = screen.getTitle().getString();

			if (!CHEST_TITLES.contains(title)) {
				return;
			}

			// Give the server a few ticks to fill the slots before summing.
			var menu = screen.getMenu();
			long total = 0;
			int priced = 0;
			int unpriced = 0;
			StringBuilder unpricedNames = new StringBuilder();

			// The chest's own slots come first; the player inventory follows
			// and must not count. A reward chest is a single 27-slot row set.
			int chestSlots = Math.min(27, menu.slots.size());
			boolean anyItem = false;

			for (int i = 0; i < chestSlots; i++) {
				ItemStack stack = menu.slots.get(i).getItem();

				if (stack.isEmpty()) {
					continue;
				}

				anyItem = true;
				OptionalLong unit = PriceTooltips.valueOf(stack);

				if (unit.isPresent()) {
					total += unit.getAsLong() * stack.getCount();
					priced++;
				} else {
					unpriced++;

					if (unpricedNames.length() < 60) {
						if (!unpricedNames.isEmpty()) {
							unpricedNames.append(", ");
						}

						unpricedNames.append(stack.getHoverName().getString());
					}
				}
			}

			if (!anyItem) {
				return; // Slots not filled yet; try again next tick.
			}

			reportedScreen = screen;

			var message = Component.literal(title + " value: ")
					.withStyle(ChatFormatting.GRAY)
					.append(Component.literal("~" + Numbers.shorten(total) + " coins")
							.withStyle(ChatFormatting.GOLD))
					.append(Component.literal("  (" + priced + " item"
									+ (priced == 1 ? "" : "s") + " priced)")
							.withStyle(ChatFormatting.DARK_GRAY));

			if (unpriced > 0) {
				message = message.append(Component.literal(
								"\n  unpriced: " + unpricedNames)
						.withStyle(ChatFormatting.DARK_GRAY));
			}

			say(message);
		});
	}

	private static void say(Component message) {
		var client = Minecraft.getInstance();

		if (client.gui != null) {
			// A blank line either side, the same breathing room the help
			// and session blocks get.
			var chat = client.gui.hud.getChat();
			chat.addClientSystemMessage(Component.empty());
			chat.addClientSystemMessage(message);
			chat.addClientSystemMessage(Component.empty());
		}
	}
}
