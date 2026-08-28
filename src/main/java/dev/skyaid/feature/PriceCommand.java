package dev.skyaid.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.skyaid.api.CoflnetApiClient;
import dev.skyaid.api.HypixelApiClient;
import dev.skyaid.parse.Bazaar;
import dev.skyaid.parse.Numbers;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /skyaid price [AH|BZ] <item>}: market prices in chat, in the user's
 * chosen format. Bazaar items show the order book and the instant prices:
 *
 * <pre>
 *   Sell order / Purchase order / Quick Sell / Quick Buy
 * </pre>
 *
 * Auction items show the recent and live bidding picture:
 *
 * <pre>
 *   Last Bid / Highest Bid / Item Quantity (active listings)
 * </pre>
 *
 * With no market prefix the bazaar is tried first and the auction house is
 * the fallback; an explicit AH or BZ pins the market. Bazaar data is
 * Hypixel's own; auction aggregates come from the community Coflnet service,
 * which receives nothing but the item name.
 */
public final class PriceCommand {
	/**
	 * Short enough that asking again genuinely re-asks Hypixel - the field
	 * complaint was flip numbers that never moved. Hypixel refreshes its own
	 * bazaar snapshot about once a minute; the answer age is shown alongside
	 * so stale data is visible instead of silent.
	 */
	private static final long CACHE_MILLIS = 20_000;

	private static final int MAX_SUGGESTIONS = 5;

	private PriceCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("price")
								// Bare "/skyaid price" must answer HERE - an
								// unmatched client command falls through to
								// the server, which shouts Unknown command.
								.executes(context -> {
									say(Component.literal(
													"Usage: /skyaid price [AH|BZ] <item>")
											.withStyle(ChatFormatting.GRAY));
									return 1;
								})
								.then(ClientCommands.argument(
												"item", StringArgumentType.greedyString())
										.executes(context -> {
											lookup(StringArgumentType.getString(context, "item"));
											return 1;
										})))
						.then(ClientCommands.literal("flips")
								.executes(context -> {
									lookupFlips();
									return 1;
								}))));
	}

	/**
	 * {@code /skyaid flips}: the best bazaar order-flip margins right now.
	 * A flip is placing a buy order just above the current top buy order and
	 * a sell order just under the cheapest sell offer; the margin between
	 * them, less the 1.25% bazaar tax, is the profit. Only liquid items are
	 * listed - a huge margin on something trading ten times a week is bait.
	 */
	static void lookupFlips() {
		say(Component.literal("Scanning the bazaar for flip margins...")
				.withStyle(ChatFormatting.GRAY));

		HypixelApiClient.get("/skyblock/bazaar", CACHE_MILLIS, false)
				.thenAccept(body -> Minecraft.getInstance().execute(
						() -> reportFlips(body)));
	}

	/** Weekly units each side must move for an item to count as liquid. */
	private static final double MIN_WEEKLY_VOLUME = 50_000;
	private static final double BAZAAR_TAX = 0.0125;
	private static final int MAX_FLIPS = 8;

	/**
	 * Real flips live between these margins. Below 2% the tax eats it; above
	 * 75% the order book is broken - a 3-coin top buy order under an 8k sell
	 * offer is not a flip, it is an empty side (lesson learned: the first cut
	 * ranked by raw margin and listed exactly that junk).
	 */
	private static final double MIN_MARGIN_PERCENT = 2;
	private static final double MAX_MARGIN_PERCENT = 75;

	private record Flip(String id, double buyAt, double sellAt, double marginPercent,
			double hourlyPotential) {
	}

	private static void reportFlips(Optional<JsonObject> body) {
		if (body.isEmpty() || body.get().getAsJsonObject("products") == null) {
			say(Component.literal("Could not reach the bazaar - try again in a moment.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		JsonObject products = body.get().getAsJsonObject("products");
		List<Flip> flips = new java.util.ArrayList<>();

		for (String id : products.keySet()) {
			JsonObject product = products.getAsJsonObject(id);
			JsonObject quick = product.getAsJsonObject("quick_status");

			if (quick == null) {
				continue;
			}

			double weekly = Math.min(quick.get("buyMovingWeek").getAsDouble(),
					quick.get("sellMovingWeek").getAsDouble());

			if (weekly < MIN_WEEKLY_VOLUME) {
				continue;
			}

			Optional<Double> sellOffer = topPrice(product.getAsJsonArray("buy_summary"));
			Optional<Double> buyOrder = topPrice(product.getAsJsonArray("sell_summary"));

			if (sellOffer.isEmpty() || buyOrder.isEmpty() || buyOrder.get() <= 0) {
				continue;
			}

			double profit = sellOffer.get() * (1 - BAZAAR_TAX) - buyOrder.get();
			double percent = 100.0 * profit / buyOrder.get();

			if (percent >= MIN_MARGIN_PERCENT && percent <= MAX_MARGIN_PERCENT) {
				// What the flip could actually pay: profit per unit times the
				// units really trading per hour. This is what ranks the list.
				flips.add(new Flip(id, buyOrder.get(), sellOffer.get(), percent,
						profit * weekly / 168.0));
			}
		}

		if (flips.isEmpty()) {
			say(Component.literal("No liquid flip margins right now.")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		flips.sort((a, b) -> Double.compare(b.hourlyPotential(), a.hourlyPotential()));

		var message = Component.literal("Bazaar flips - place a buy order, resell at the"
						+ " sell price (tax counted):")
				.withStyle(ChatFormatting.AQUA);

		for (Flip flip : flips.subList(0, Math.min(MAX_FLIPS, flips.size()))) {
			String name = Bazaar.displayName(flip.id());

			if (name.length() > 20) {
				name = name.substring(0, 19) + ".";
			}

			message = message.copy()
					.append(Component.literal("\n  "))
					.append(button(name,
							"/bz " + dev.skyaid.parse.ItemNames.cleanForSearch(
									Bazaar.displayName(flip.id())),
							"Opens the bazaar for this item", ChatFormatting.WHITE))
					.append(Component.literal(
									" ".repeat(Math.max(1, 21 - name.length()))
											+ String.format(Locale.ROOT, "%9s -> %-9s",
											Numbers.shorten(Math.round(flip.buyAt())),
											Numbers.shorten(Math.round(flip.sellAt()))))
							.withStyle(ChatFormatting.GOLD))
					.append(Component.literal(String.format(Locale.ROOT, " %5.1f%%",
									flip.marginPercent()))
							.withStyle(ChatFormatting.GREEN))
					.append(Component.literal(String.format(Locale.ROOT, "  ~%s/hr",
									Numbers.shorten(Math.round(flip.hourlyPotential()))))
							.withStyle(ChatFormatting.DARK_GRAY));
		}

		message = message.copy().append(Component.literal(
						"\n  Margin is per item; /hr assumes you match the item's real"
								+ " trade flow.")
				.withStyle(ChatFormatting.DARK_GRAY));

		message = message.copy().append(dataAgeLine(body.get()));

		say(message);
	}

	private static void lookup(String rawQuery) {
		String query = rawQuery.trim();
		String market = "";

		// An AH or BZ in front pins the market; anything else is item name.
		String[] parts = query.split("\\s+", 2);

		if (parts.length == 2) {
			String first = parts[0].toUpperCase(Locale.ROOT);

			if (first.equals("AH") || first.equals("BZ")) {
				market = first;
				query = parts[1];
			}
		}

		if (query.isEmpty()) {
			say(Component.literal("Usage: /skyaid price [AH|BZ] <item>")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		say(Component.literal("Checking " + (market.isEmpty() ? "the markets"
						: market.equals("BZ") ? "the bazaar" : "the auction house") + "...")
				.withStyle(ChatFormatting.GRAY));

		if (market.equals("AH")) {
			lookupAuctions(query);
			return;
		}

		boolean bazaarOnly = market.equals("BZ");
		String itemQuery = query;

		HypixelApiClient.get("/skyblock/bazaar", CACHE_MILLIS, false)
				.thenAccept(body -> Minecraft.getInstance().execute(
						() -> reportBazaar(itemQuery, body, bazaarOnly)));
	}

	private static void reportBazaar(
			String query, Optional<JsonObject> body, boolean bazaarOnly) {
		if (body.isEmpty()) {
			say(Component.literal("Could not reach the bazaar - try again in a moment.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		JsonObject products = body.get().getAsJsonObject("products");

		if (products == null) {
			say(Component.literal("The bazaar answered with nothing usable.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		List<String> matches = Bazaar.match(products.keySet(), query);

		if (matches.isEmpty()) {
			if (bazaarOnly) {
				say(Component.literal("Nothing on the bazaar matches \"" + query + "\".")
						.withStyle(ChatFormatting.GRAY));
			} else {
				lookupAuctions(query);
			}

			return;
		}

		if (matches.size() > 1 && !Bazaar.normalise(query).equals(matches.get(0))) {
			var lines = Component.literal("Several items match - click one:")
					.withStyle(ChatFormatting.YELLOW);

			for (String id : matches.subList(0, Math.min(MAX_SUGGESTIONS, matches.size()))) {
				lines = lines.copy().append(Component.literal("\n  "))
						.append(button(Bazaar.displayName(id),
								"/skyaid price BZ " + Bazaar.displayName(id),
								"Click to price it", ChatFormatting.WHITE));
			}

			say(lines);
			return;
		}

		String id = matches.get(0);
		JsonObject product = products.getAsJsonObject(id);
		JsonObject quick = product.getAsJsonObject("quick_status");

		// The order book's top of each side: where sell offers sit (what an
		// undercutting seller must beat) and where buy orders sit.
		Optional<Double> sellOrder = topPrice(product.getAsJsonArray("buy_summary"));
		Optional<Double> purchaseOrder = topPrice(product.getAsJsonArray("sell_summary"));

		var message = Component.literal(Bazaar.displayName(id)).withStyle(ChatFormatting.AQUA)
				.append(priceLine("Sell order:     ", sellOrder))
				.append(priceLine("Purchase order: ", purchaseOrder));

		if (quick != null) {
			message = message
					.append(priceLine("Quick Sell:     ",
							Optional.of(quick.get("sellPrice").getAsDouble())))
					.append(priceLine("Quick Buy:      ",
							Optional.of(quick.get("buyPrice").getAsDouble())));
		}

		message = message.append(Component.literal("\n  "))
				.append(button("[Open Bazaar]",
						"/bz " + dev.skyaid.parse.ItemNames.cleanForSearch(Bazaar.displayName(id)),
						"Opens the bazaar search for this item", ChatFormatting.GOLD));

		message = message.copy().append(dataAgeLine(body.get()));

		say(message);
		appendHistory(id);
	}

	/**
	 * A follow-up "7d avg" line once the price service's daily aggregates
	 * arrive - its own message so the instant answer never waits on it.
	 */
	private static void appendHistory(String tag) {
		CoflnetApiClient.priceHistory(tag).thenAccept(history ->
				Minecraft.getInstance().execute(() -> {
					if (history.isEmpty() || history.get().isEmpty()) {
						return;
					}

					JsonArray days = history.get();
					double sum = 0;
					int counted = 0;

					for (JsonElement day : days) {
						JsonObject entry = day.getAsJsonObject();

						if (entry.has("avg")) {
							sum += entry.get("avg").getAsDouble();
							counted++;
						}
					}

					if (counted == 0) {
						return;
					}

					JsonObject first = days.get(0).getAsJsonObject();
					JsonObject last = days.get(days.size() - 1).getAsJsonObject();
					var line = Component.literal("  7d avg:        ")
							.withStyle(ChatFormatting.GRAY)
							.append(Component.literal(
											Numbers.group(Math.round(sum / counted)) + " coins")
									.withStyle(ChatFormatting.GOLD));

					if (first.has("avg") && last.has("avg")
							&& first.get("avg").getAsDouble() > 0) {
						double change = 100 * (last.get("avg").getAsDouble()
								- first.get("avg").getAsDouble())
								/ first.get("avg").getAsDouble();
						line = line.append(Component.literal(String.format(
										Locale.ROOT, "  %+.1f%% this week", change))
								.withStyle(change >= 0
										? ChatFormatting.GREEN : ChatFormatting.RED));
					}

					say(line);
				}));
	}

	private static Optional<Double> topPrice(JsonArray summary) {
		if (summary == null || summary.isEmpty()) {
			return Optional.empty();
		}

		JsonObject top = summary.get(0).getAsJsonObject();
		return top.has("pricePerUnit")
				? Optional.of(top.get("pricePerUnit").getAsDouble()) : Optional.empty();
	}

	private static Component priceLine(String label, Optional<Double> value) {
		return Component.literal("\n  " + label).withStyle(ChatFormatting.GRAY)
				.append(value.isEmpty()
						? Component.literal("none").withStyle(ChatFormatting.DARK_GRAY)
						: Component.literal(Numbers.group(Math.round(value.get())) + " coins")
								.withStyle(ChatFormatting.GOLD));
	}

	/**
	 * The auction block: resolve the typed name to an item tag via the price
	 * service's search, then combine its recent sales and active listings.
	 */
	private static void lookupAuctions(String query) {
		CoflnetApiClient.searchItems(query).thenAccept(found ->
				Minecraft.getInstance().execute(() -> {
					if (found.isEmpty() || found.get().isEmpty()) {
						say(Component.literal("Nothing on the auction house matches \""
										+ query + "\".")
								.withStyle(ChatFormatting.GRAY));
						return;
					}

					JsonObject item = found.get().get(0).getAsJsonObject();
					String tag = item.get("id").getAsString();
					String name = item.has("name")
							? item.get("name").getAsString() : Bazaar.displayName(tag);

					CoflnetApiClient.recentSales(tag).thenCombine(
									CoflnetApiClient.activeAuctions(tag),
									(recent, active) -> new JsonArray[]{
											recent.orElse(new JsonArray()),
											active.orElse(new JsonArray())})
							.thenAccept(results -> Minecraft.getInstance().execute(
									() -> reportAuctions(name, tag, results[0], results[1])));
				}));
	}

	private static void reportAuctions(
			String name, String tag, JsonArray recent, JsonArray active) {
		Optional<Double> lastBid = recent.isEmpty() ? Optional.empty()
				: Optional.of(recent.get(0).getAsJsonObject().get("price").getAsDouble());

		Optional<Double> highestBid = Optional.empty();

		for (JsonElement element : active) {
			double price = element.getAsJsonObject().get("price").getAsDouble();

			if (highestBid.isEmpty() || price > highestBid.get()) {
				highestBid = Optional.of(price);
			}
		}

		say(Component.literal(name).withStyle(ChatFormatting.AQUA)
				.append(priceLine("Last Bid:      ", lastBid))
				.append(priceLine("Highest Bid:   ", highestBid))
				.append(Component.literal("\n  Item Quantity: ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(active.size() + " listed")
						.withStyle(ChatFormatting.WHITE))
				.append(Component.literal("\n  "))
				.append(button("[Search AH]",
						"/ahs " + dev.skyaid.parse.ItemNames.cleanForSearch(name),
						"Opens the AH search for this item", ChatFormatting.AQUA)));
		appendHistory(tag);
	}

	/**
	 * A clickable [label] that runs ONE command when the user clicks it - the
	 * same user-initiated, one-command-per-action shape as the F1/F2 keys.
	 * Names are cleaned first: Hypixel kicks for fancy glyphs in chat.
	 */
	private static Component button(
			String label, String command, String hint, ChatFormatting colour) {
		return Component.literal(label).withStyle(style -> style
				.withColor(colour)
				.withUnderlined(true)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(command))
				.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
						Component.literal(hint).withStyle(ChatFormatting.GRAY))));
	}

	/**
	 * "Bazaar data from 12s ago." Hypixel serves a cached snapshot, so the
	 * age is real information: it says how live the numbers actually are.
	 */
	private static Component dataAgeLine(JsonObject body) {
		if (!body.has("lastUpdated")) {
			return Component.empty();
		}

		long age = Math.max(0,
				(System.currentTimeMillis() - body.get("lastUpdated").getAsLong()) / 1000);
		return Component.literal("\n  Bazaar data from " + age + "s ago.")
				.withStyle(ChatFormatting.DARK_GRAY);
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
