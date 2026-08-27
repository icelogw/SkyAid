package dev.skyaid.feature;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import java.util.Map;
import java.util.Optional;

/**
 * {@code /skyaid craft <item>}: is compressing the base item into its
 * enchanted form worth it at live prices? Buys the base at insta-buy, sells
 * the craft at insta-sell, and says which side of zero the margin lands.
 *
 * <p>Most enchanted crafts compress 160:1; the exceptions this table knows
 * are listed, and the answer always names the ratio it assumed - an unknown
 * recipe is an honest estimate, not a fact.
 */
public final class CraftProfit {
	private static final long CACHE_MILLIS = 20_000;
	private static final int DEFAULT_RATIO = 160;

	/** Crafts that do not follow the 160:1 convention. */
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
															+ " /skyaid craft sugar cane")
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
						() -> report(query.trim(), body)));
	}

	private static void report(String query, Optional<JsonObject> body) {
		JsonObject products = body.map(json -> json.getAsJsonObject("products"))
				.orElse(null);

		if (products == null) {
			say(Component.literal("Could not reach the bazaar - try again in a moment.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		List<String> matches = Bazaar.match(products.keySet(), query);

		if (matches.isEmpty()) {
			say(Component.literal("Nothing on the bazaar matches \"" + query + "\".")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		// Whichever side was named, work out the base -> enchanted pair.
		String id = matches.get(0);
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
			say(Component.literal(Bazaar.displayName(id)
							+ " has no enchanted craft pair on the bazaar.")
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

		var message = Component.literal(Bazaar.displayName(crafted))
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
								? ChatFormatting.GREEN : ChatFormatting.RED));

		if (!RATIOS.containsKey(crafted)) {
			message = message.copy().append(Component.literal(
							"\n  Assumes the usual 160:1 recipe.")
					.withStyle(ChatFormatting.DARK_GRAY));
		}

		say(message);
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
