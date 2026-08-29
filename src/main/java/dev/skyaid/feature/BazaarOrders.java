package dev.skyaid.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.skyaid.api.HypixelApiClient;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.parse.Bazaar;
import dev.skyaid.parse.Numbers;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers the bazaar orders seen in the "Your Bazaar Orders" menu and,
 * afterwards, quietly compares them against the live order book - one padded
 * chat line the moment an offer of yours is undercut (or a buy order outbid),
 * so the answer to "am I still top?" doesn't require reopening the bazaar.
 *
 * <p>Reading only: the orders are whatever the menu showed, the check is the
 * public bazaar API, and nothing is re-listed for you. The menu title and
 * lore wording are ecosystem knowledge - the dump's LAST CONTAINER section
 * corrects a mismatch. Orders are forgotten when the session ends.
 */
public final class BazaarOrders {
	private static final long CHECK_INTERVAL_MILLIS = 60_000;
	private static final long API_TTL_MILLIS = 45_000;

	/** "Price per unit: 4.2 coins" in an order's lore. */
	private static final Pattern PRICE_LINE = Pattern.compile(
			"Price per unit:\\s*([\\d,.]+) coins?.*");

	private record Order(String display, boolean sell, double price) {
	}

	/** Key "SELL|Enchanted Carrot" -> the order; latest sighting wins. */
	private static final Map<String, Order> orders = new HashMap<>();

	/** Orders already alerted, so one undercut is one line. */
	private static final Map<String, Double> alertedAt = new HashMap<>();

	private static int scanCounter;
	private static long lastCheckAt;

	private BazaarOrders() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ConfigManager.get().enabled || !ConfigManager.get().bazaarWatchdog
					|| client.player == null || !HypixelDetector.isOnHypixel()) {
				return;
			}

			if (client.gui != null && client.gui.screen()
					instanceof AbstractContainerScreen<?> screen
					&& screen.getTitle().getString().contains("Bazaar Orders")) {
				if (++scanCounter >= 10) {
					scanCounter = 0;
					capture(screen);
				}

				return;
			}

			long now = System.currentTimeMillis();

			if (!orders.isEmpty() && now - lastCheckAt >= CHECK_INTERVAL_MILLIS) {
				lastCheckAt = now;
				HypixelApiClient.get("/skyblock/bazaar", API_TTL_MILLIS, false)
						.thenAccept(body -> body.ifPresent(json ->
								Minecraft.getInstance().execute(() -> check(json))));
			}
		});
	}

	/** Every order row: "BUY/SELL <item>" name plus its price-per-unit lore. */
	private static void capture(AbstractContainerScreen<?> screen) {
		for (var slot : screen.getMenu().slots) {
			var stack = slot.getItem();

			if (stack.isEmpty()) {
				continue;
			}

			String name = stack.getHoverName().getString().trim();
			boolean sell = name.startsWith("SELL ");
			boolean buy = name.startsWith("BUY ");

			if (!sell && !buy) {
				continue;
			}

			var lore = stack.get(DataComponents.LORE);

			if (lore == null) {
				continue;
			}

			for (Component line : lore.lines()) {
				Matcher price = PRICE_LINE.matcher(line.getString().trim());

				if (price.matches()) {
					String item = name.substring(sell ? 5 : 4).trim();
					String key = (sell ? "SELL|" : "BUY|") + item;
					Order previous = orders.put(key, new Order(
							item, sell, Double.parseDouble(
									price.group(1).replace(",", ""))));

					// A re-listed order is a new race: alert again. Logged
					// only then - the menu rescans every half second.
					if (previous == null || previous.price() != orders.get(key).price()) {
						alertedAt.remove(key);
						dev.skyaid.core.EventLog.event("bazaar", "order captured: "
								+ (sell ? "SELL " : "BUY ") + item
								+ " @ " + orders.get(key).price());
					}

					break;
				}
			}
		}
	}

	private static void check(JsonObject body) {
		JsonObject products = body.getAsJsonObject("products");

		if (products == null) {
			return;
		}

		for (Map.Entry<String, Order> entry : orders.entrySet()) {
			Order order = entry.getValue();
			List<String> match = Bazaar.match(products.keySet(), order.display());

			if (match.isEmpty()) {
				continue;
			}

			JsonObject product = products.getAsJsonObject(match.get(0));

			// buy_summary holds standing SELL offers (what a seller must
			// beat); sell_summary the standing BUY orders.
			double top = topPrice(product.getAsJsonArray(
					order.sell() ? "buy_summary" : "sell_summary"));

			if (top <= 0) {
				continue;
			}

			boolean beaten = order.sell()
					? top < order.price() - 0.05
					: top > order.price() + 0.05;

			if (!beaten) {
				alertedAt.remove(entry.getKey());
				continue;
			}

			Double already = alertedAt.get(entry.getKey());

			if (already != null && Math.abs(already - top) < 0.05) {
				continue;
			}

			dev.skyaid.core.EventLog.event("bazaar", (order.sell() ? "undercut: " : "outbid: ") + order.display() + " yours " + order.price() + " top " + top);
			alertedAt.put(entry.getKey(), top);
			say(Component.literal((order.sell() ? "Undercut: " : "Outbid: "))
					.withStyle(ChatFormatting.RED)
					.append(Component.literal(order.display())
							.withStyle(ChatFormatting.WHITE))
					.append(Component.literal(String.format(Locale.ROOT,
									" - yours %s, top now %s",
									Numbers.shorten(Math.round(order.price())),
									Numbers.shorten(Math.round(top))))
							.withStyle(ChatFormatting.GRAY)));
		}
	}

	private static double topPrice(JsonArray summary) {
		if (summary == null || summary.isEmpty()) {
			return 0;
		}

		JsonObject first = summary.get(0).getAsJsonObject();
		return first.has("pricePerUnit")
				? first.get("pricePerUnit").getAsDouble() : 0;
	}

	public static void dumpInto(StringBuilder out) {
		out.append("\nBAZAAR ORDERS (watchdog):\n");

		if (orders.isEmpty()) {
			out.append("  (none captured - open Your Bazaar Orders once)\n");
			return;
		}

		for (Order order : orders.values()) {
			out.append("  ").append(order.sell() ? "SELL " : "BUY ")
					.append(order.display()).append(" @ ")
					.append(order.price()).append('\n');
		}
	}

	private static void say(Component message) {
		var client = Minecraft.getInstance();

		if (client.gui != null) {
			var chat = client.gui.hud.getChat();
			chat.addClientSystemMessage(Component.empty());
			chat.addClientSystemMessage(message);
			chat.addClientSystemMessage(Component.empty());
		}
	}
}
