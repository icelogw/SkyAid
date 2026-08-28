package dev.skyaid.feature;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.skyaid.api.HypixelApiClient;
import dev.skyaid.parse.Bazaar;
import dev.skyaid.parse.FormatCodes;
import dev.skyaid.parse.Numbers;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /skyaid craft <item>}: what crafting an item costs at live prices
 * versus what it sells for. Recipes come from the NEU community repository
 * (one small JSON per item id, cached); ingredients price at bazaar
 * insta-buy or lowest BIN, the result at insta-sell or BIN.
 *
 * <p>Bazaar names resolve directly; anything else goes through the auction
 * price service's item search, so AH-only craftables work too. Without a
 * known recipe, enchanted-compression items fall back to the usual 160:1
 * with the assumption stated.
 */
public final class CraftProfit {
	private static final long CACHE_MILLIS = 20_000;
	private static final int DEFAULT_RATIO = 160;

	private static final String NEU_BASE = "https://raw.githubusercontent.com"
			+ "/NotEnoughUpdates/NotEnoughUpdates-REPO/master/items/";

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	/** Item id -> its NEU json (empty = known absent); session cache. */
	private static final Map<String, Optional<JsonObject>> NEU_CACHE =
			new ConcurrentHashMap<>();

	/** Compressions that do not follow the 160:1 convention (fallback only). */
	private static final Map<String, Integer> RATIOS = Map.of(
			"ENCHANTED_BREAD", 60,
			"ENCHANTED_PAPER", 192,
			"ENCHANTED_HAY_BLOCK", 1296,
			"ENCHANTED_MELON_BLOCK", 25_600,
			"ENCHANTED_GLOWSTONE", 24_576);

	private CraftProfit() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("craft")
								.executes(context -> {
									say(Component.literal(
													"Usage: /skyaid craft <item> - e.g."
															+ " /skyaid craft personal compactor 7000")
											.withStyle(ChatFormatting.GRAY));
									return 1;
								})
								.then(ClientCommands.argument("item",
												StringArgumentType.greedyString())
										.executes(context -> {
											lookup(StringArgumentType.getString(
													context, "item"));
											return 1;
										})))));
	}

	private static void lookup(String query) {
		HypixelApiClient.get("/skyblock/bazaar", CACHE_MILLIS, false)
				.thenAccept(body -> Minecraft.getInstance().execute(
						() -> resolve(query.trim(), body)));
	}

	/** Bazaar name first; else the auction item search names the id. */
	private static void resolve(String query, Optional<JsonObject> body) {
		JsonObject products = body.map(json -> json.getAsJsonObject("products"))
				.orElse(null);

		List<String> matches = products == null
				? List.of() : Bazaar.match(products.keySet(), query);

		if (!matches.isEmpty()) {
			report(matches.get(0), products);
			return;
		}

		dev.skyaid.api.CoflnetApiClient.searchItems(query).thenAccept(found ->
				Minecraft.getInstance().execute(() -> {
					if (found.isEmpty() || found.get().isEmpty()) {
						say(Component.literal("Nothing matches \"" + query + "\".")
								.withStyle(ChatFormatting.GRAY));
						return;
					}

					report(found.get().get(0).getAsJsonObject()
							.get("id").getAsString(), products);
				}));
	}

	private static void report(String id, JsonObject products) {
		say(Component.literal("Pricing the " + prettyName(id, null) + " recipe...")
				.withStyle(ChatFormatting.GRAY));

		neuItem(id).thenAccept(item -> Minecraft.getInstance().execute(() -> {
			Map<String, Long> ingredients = item.map(CraftProfit::recipeOf)
					.orElse(null);

			if (ingredients == null || ingredients.isEmpty()) {
				fallback(id, products);
				return;
			}

			long cost = 0;
			int unpriced = 0;
			var message = Component.literal(prettyName(id, item.orElse(null)))
					.withStyle(ChatFormatting.AQUA);

			for (Map.Entry<String, Long> need : ingredients.entrySet()) {
				String line;

				if (need.getKey().equals("SKYBLOCK_COIN")) {
					cost += need.getValue();
					line = "  " + Numbers.group(need.getValue()) + " coins";
				} else {
					var unit = PriceTooltips.buyPriceOf(need.getKey());

					if (unit.isPresent()) {
						long total = unit.getAsLong() * need.getValue();
						cost += total;
						line = String.format(Locale.ROOT, "  %,dx %s = %s",
								need.getValue(), prettyName(need.getKey(), null),
								Numbers.shorten(total));
					} else {
						unpriced++;
						line = String.format(Locale.ROOT, "  %,dx %s = ?",
								need.getValue(), prettyName(need.getKey(), null));
					}
				}

				message = message.copy().append(Component.literal("\n" + line)
						.withStyle(ChatFormatting.GRAY));
			}

			message = message.copy()
					.append(Component.literal("\n  Craft cost: ")
							.withStyle(ChatFormatting.GRAY))
					.append(Component.literal("~" + Numbers.shorten(cost)
									+ (unpriced > 0 ? "  (" + unpriced
											+ " ingredient(s) unpriced - rerun shortly)"
											: ""))
							.withStyle(ChatFormatting.GOLD));

			var worth = PriceTooltips.sellValueById(id);

			if (worth.isPresent()) {
				long margin = worth.getAsLong() - cost;
				message = message.copy()
						.append(Component.literal("\n  Sells for:  ")
								.withStyle(ChatFormatting.GRAY))
						.append(Component.literal("~" + Numbers.shorten(worth.getAsLong()))
								.withStyle(ChatFormatting.GOLD))
						.append(Component.literal("\n  Margin:     ")
								.withStyle(ChatFormatting.GRAY))
						.append(Component.literal(String.format(Locale.ROOT, "%s%s",
										margin >= 0 ? "+" : "-",
										Numbers.shorten(Math.abs(margin))))
								.withStyle(margin >= 0
										? ChatFormatting.GREEN : ChatFormatting.RED));
			}

			say(message);
		}));
	}

	/** The old enchanted-compression estimate, when NEU has no recipe. */
	private static void fallback(String id, JsonObject products) {
		if (products == null) {
			say(Component.literal("No recipe found for " + prettyName(id, null) + ".")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		String base;
		String crafted;

		if (id.startsWith("ENCHANTED_")) {
			base = id.substring("ENCHANTED_".length());
			crafted = id;
		} else {
			base = id;
			crafted = "ENCHANTED_" + id;
		}

		if (!products.has(base) || !products.has(crafted)) {
			say(Component.literal("No recipe found for " + prettyName(id, null) + ".")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		int ratio = RATIOS.getOrDefault(crafted, DEFAULT_RATIO);
		JsonObject baseQuick = products.getAsJsonObject(base)
				.getAsJsonObject("quick_status");
		JsonObject craftedQuick = products.getAsJsonObject(crafted)
				.getAsJsonObject("quick_status");

		if (baseQuick == null || craftedQuick == null) {
			say(Component.literal("The bazaar answered with nothing usable.")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		double cost = baseQuick.get("buyPrice").getAsDouble() * ratio;
		double sale = craftedQuick.get("sellPrice").getAsDouble();
		double margin = sale - cost;

		say(Component.literal(Bazaar.displayName(crafted))
				.withStyle(ChatFormatting.AQUA)
				.append(Component.literal("\n  Craft cost:  ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(Numbers.shorten(Math.round(cost))
								+ "  (" + Numbers.group(ratio) + "x "
								+ Bazaar.displayName(base) + " insta-buy)")
						.withStyle(ChatFormatting.GOLD))
				.append(Component.literal("\n  Sells for:   ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(Numbers.shorten(Math.round(sale))
								+ "  (insta-sell)")
						.withStyle(ChatFormatting.GOLD))
				.append(Component.literal("\n  Margin:      ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(String.format(Locale.ROOT, "%s%s per craft",
								margin >= 0 ? "+" : "-",
								Numbers.shorten(Math.round(Math.abs(margin)))))
						.withStyle(margin >= 0
								? ChatFormatting.GREEN : ChatFormatting.RED))
				.append(Component.literal("\n  Assumes the usual "
								+ ratio + ":1 recipe.")
						.withStyle(ChatFormatting.DARK_GRAY)));
	}

	/**
	 * Ingredient id -> total count, from the item's NEU json: the modern
	 * "recipes" array's crafting entry, or the legacy "recipe" object. Slots
	 * are "ID:count" strings; empty slots are skipped.
	 */
	private static Map<String, Long> recipeOf(JsonObject item) {
		JsonObject grid = null;

		if (item.has("recipes") && item.get("recipes").isJsonArray()) {
			for (JsonElement entry : item.getAsJsonArray("recipes")) {
				if (!entry.isJsonObject()) {
					continue;
				}

				JsonObject candidate = entry.getAsJsonObject();
				String type = candidate.has("type")
						? candidate.get("type").getAsString() : "crafting";

				if (type.equals("crafting")) {
					grid = candidate;
					break;
				}
			}
		}

		if (grid == null && item.has("recipe") && item.get("recipe").isJsonObject()) {
			grid = item.getAsJsonObject("recipe");
		}

		if (grid == null) {
			return Map.of();
		}

		Map<String, Long> needed = new LinkedHashMap<>();

		for (String row : new String[]{"A", "B", "C"}) {
			for (int column = 1; column <= 3; column++) {
				String slot = row + column;

				if (!grid.has(slot) || !grid.get(slot).isJsonPrimitive()) {
					continue;
				}

				String value = grid.get(slot).getAsString().trim();

				if (value.isEmpty()) {
					continue;
				}

				int colon = value.lastIndexOf(':');
				String ingredient = colon > 0 ? value.substring(0, colon) : value;
				long count = 1;

				if (colon > 0) {
					try {
						count = Math.round(Double.parseDouble(
								value.substring(colon + 1)));
					} catch (NumberFormatException e) {
						// "ID-1" style variants keep the whole string as id.
						ingredient = value;
					}
				}

				needed.merge(ingredient, count, Long::sum);
			}
		}

		return needed;
	}

	/** The NEU json for an id, fetched once per session. */
	private static java.util.concurrent.CompletableFuture<Optional<JsonObject>>
			neuItem(String id) {
		Optional<JsonObject> cached = NEU_CACHE.get(id);

		if (cached != null) {
			return java.util.concurrent.CompletableFuture.completedFuture(cached);
		}

		if (!id.matches("[A-Z0-9_;:-]{1,64}")) {
			return java.util.concurrent.CompletableFuture.completedFuture(
					Optional.empty());
		}

		HttpRequest request = HttpRequest.newBuilder(
						URI.create(NEU_BASE + id + ".json"))
				.timeout(Duration.ofSeconds(15))
				.GET()
				.build();

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.handle((response, error) -> {
					Optional<JsonObject> parsed = Optional.empty();

					try {
						if (error == null && response.statusCode() == 200) {
							parsed = Optional.of(JsonParser.parseString(
									response.body()).getAsJsonObject());
						}
					} catch (Exception e) {
						// An unparsable item file reads as recipe-less.
					}

					NEU_CACHE.put(id, parsed);
					return parsed;
				});
	}

	/** The NEU display name when known, else the id prettified. */
	private static String prettyName(String id, JsonObject item) {
		Optional<JsonObject> cached = item != null
				? Optional.of(item) : NEU_CACHE.getOrDefault(id, Optional.empty());

		if (cached.isPresent() && cached.get().has("displayname")) {
			return FormatCodes.strip(cached.get().get("displayname").getAsString());
		}

		return Bazaar.displayName(id);
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
