package dev.skyaid.feature;

import com.google.gson.JsonObject;
import dev.skyaid.api.HypixelApiClient;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.parse.Bazaar;
import dev.skyaid.parse.Numbers;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Prices what a Garden visitor asks for: when a visitor's menu is open, the
 * "Items Required" list is read from the menu items' lore, each item priced
 * at the live bazaar insta-buy cost, and ONE chat line totals it - so
 * accepting or declining is an informed choice.
 *
 * <p>The lore wording ("Items Required") is ecosystem knowledge, UNVERIFIED -
 * the dump's GARDEN CONTAINER section prints an open menu's exact lore so a
 * mismatch is a capture away from fixed.
 */
public final class VisitorCost {
	private static final long CACHE_MILLIS = 20_000;

	/** The screen already reported, so the line is said once per menu. */
	private static Object reportedScreen;

	/**
	 * The last open container, captured live so /skyaid dump can print it
	 * AFTER it closes - chat cannot be typed while a menu is open.
	 */
	private static String lastContainerTitle = "(none yet)";
	private static final List<String> lastContainerLines = new ArrayList<>();
	private static int snapshotCounter;

	private VisitorCost() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ConfigManager.get().enabled
					|| !(client.gui != null
							&& client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
				return;
			}

			if (++snapshotCounter >= 10) {
				snapshotCounter = 0;
				snapshot(screen);
			}

			if (screen == reportedScreen || !onGarden()) {
				return;
			}

			List<String[]> wanted = requiredItems(screen);

			if (wanted.isEmpty()) {
				return; // Not a visitor menu, or the slots are still loading.
			}

			reportedScreen = screen;

			HypixelApiClient.get("/skyblock/bazaar", CACHE_MILLIS, false)
					.thenAccept(body -> Minecraft.getInstance().execute(
							() -> report(wanted, body)));
		});
	}

	private static boolean onGarden() {
		return ConfigManager.get().debug || SkyblockTracker.state().location()
				.map(zone -> zone.contains("Garden") || zone.startsWith("Plot"))
				.orElse(false);
	}

	/**
	 * Every "Name xN" line under an "Items Required" lore header across the
	 * menu's items, as {name, amount} pairs.
	 */
	private static List<String[]> requiredItems(AbstractContainerScreen<?> screen) {
		List<String[]> wanted = new ArrayList<>();

		for (var slot : screen.getMenu().slots) {
			ItemStack stack = slot.getItem();

			if (stack.isEmpty()) {
				continue;
			}

			var lore = stack.get(DataComponents.LORE);

			if (lore == null) {
				continue;
			}

			boolean inRequired = false;

			for (Component line : lore.lines()) {
				String text = line.getString().trim();

				if (text.startsWith("Items Required")) {
					inRequired = true;
					continue;
				}

				if (!inRequired) {
					continue;
				}

				if (text.isEmpty() || text.startsWith("Reward")) {
					inRequired = false;
					continue;
				}

				// "Enchanted Carrot x2" - a missing count means one.
				String name = text;
				String amount = "1";
				int marker = text.lastIndexOf(" x");

				// "x1,024" - thousands separators count as digits here.
				if (marker > 0 && marker + 2 < text.length()
						&& text.substring(marker + 2).chars()
								.allMatch(c -> Character.isDigit(c) || c == ',')) {
					String digits = text.substring(marker + 2).replace(",", "");

					if (!digits.isEmpty()) {
						name = text.substring(0, marker).trim();
						amount = digits;
					}
				}

				if (!name.isEmpty()) {
					wanted.add(new String[]{name, amount});
				}
			}
		}

		return wanted;
	}

	private static void report(List<String[]> wanted, Optional<JsonObject> body) {
		JsonObject products = body.map(json -> json.getAsJsonObject("products"))
				.orElse(null);

		if (products == null) {
			return; // No prices, no line - the menu speaks for itself.
		}

		long total = 0;
		var message = Component.literal("Visitor asks:").withStyle(ChatFormatting.AQUA);

		for (String[] item : wanted) {
			long amount = Long.parseLong(item[1]);
			List<String> match = Bazaar.match(products.keySet(), item[0]);
			String priceText = "?";

			if (!match.isEmpty()) {
				JsonObject quick = products.getAsJsonObject(match.get(0))
						.getAsJsonObject("quick_status");

				if (quick != null && quick.has("buyPrice")) {
					long cost = Math.round(quick.get("buyPrice").getAsDouble() * amount);
					total += cost;
					priceText = Numbers.shorten(cost);
				}
			}

			message = message.copy().append(Component.literal(
							String.format(Locale.ROOT, "  %sx %s = %s",
									item[1], item[0], priceText))
					.withStyle(ChatFormatting.GRAY));
		}

		message = message.copy()
				.append(Component.literal("  total ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal("~" + Numbers.shorten(total))
						.withStyle(ChatFormatting.GOLD))
				.append(Component.literal(" insta-buy").withStyle(ChatFormatting.DARK_GRAY));

		// The ledger watches whether these items then LEAVE the inventory -
		// that is what "the offer was accepted" looks like from the client.
		VisitorLedger.noteOffer(total, wanted);

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

	/** Remembers the open container: title plus every item and its lore. */
	private static void snapshot(AbstractContainerScreen<?> screen) {
		lastContainerTitle = screen.getTitle().getString();
		lastContainerLines.clear();

		for (var slot : screen.getMenu().slots) {
			if (lastContainerLines.size() > 400) {
				lastContainerLines.add("... (more cut)");
				break;
			}

			ItemStack stack = slot.getItem();

			if (stack.isEmpty()) {
				continue;
			}

			// The id says WHICH item draws the icon - a name alone cannot
			// (the AH's gavel turned out to be plain golden horse armor).
			lastContainerLines.add("item: \"" + stack.getHoverName().getString()
					+ "\" (" + net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getKey(stack.getItem()).getPath() + ")");

			// Custom heads carry their skin in the profile component - the
			// capture that lets a texture be reused (e.g. the dark globe).
			try {
				var profile = stack.get(
						net.minecraft.core.component.DataComponents.PROFILE);

				if (profile != null) {
					for (var property : profile.partialProfile().properties()
							.get("textures")) {
						lastContainerLines.add("  head: " + property.value());
					}
				}
			} catch (Exception e) {
				// A head that will not read stays a name-only line.
			}
			var lore = stack.get(DataComponents.LORE);

			if (lore != null) {
				for (Component line : lore.lines()) {
					lastContainerLines.add("  | " + line.getString());
				}
			}
		}
	}

	/** The LAST container seen, so the dump works after it closes. */
	public static void dumpInto(StringBuilder out) {
		out.append("\nLAST CONTAINER (captured while it was open):\n");
		out.append("  title: \"").append(lastContainerTitle).append("\"\n");

		if (lastContainerLines.isEmpty()) {
			out.append("  (no items captured)\n");
			return;
		}

		for (String line : lastContainerLines) {
			out.append("  ").append(line).append('\n');
		}
	}
}
