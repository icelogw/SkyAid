package dev.skyaid.feature;

import com.google.gson.JsonObject;
import dev.skyaid.api.HypixelApiClient;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.parse.Numbers;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A bazaar price line at the bottom of item tooltips: hover anything with a
 * bazaar listing and see "Bazaar: 1,350 buy - 1,266 sell" without leaving the
 * inventory.
 *
 * <p>Hypixel stamps every Skyblock item's identity into its NBT
 * (ExtraAttributes.id), which survives into the modern client's custom-data
 * component - the same id spelling the bazaar uses. Prices come from one
 * shared background snapshot refreshed every minute; a tooltip never blocks
 * on the network, it just shows the line once the snapshot exists.
 */
public final class PriceTooltips {
	private static final long REFRESH_MILLIS = 60_000;

	/** Skyblock item id -> {insta-buy, insta-sell}, whole coins. */
	private static volatile Map<String, long[]> prices = Map.of();
	private static volatile long fetchedAt;
	private static final AtomicBoolean fetching = new AtomicBoolean();

	// Failed fetches wait this long before retrying - tooltips redraw every
	// frame, and an API outage must not become a request per frame.
	private static final long RETRY_FLOOR_MILLIS = 10_000;
	private static volatile long attemptAt;
	private static volatile long npcAttemptAt;

	/**
	 * Skyblock item id -> NPC sell price, from the keyless items resource.
	 * NPC prices change with game updates, not the market - a day's cache.
	 */
	private static final long NPC_REFRESH_MILLIS = 24 * 60 * 60_000L;
	private static volatile Map<String, Long> npcPrices = Map.of();
	private static volatile long npcFetchedAt;
	private static final AtomicBoolean npcFetching = new AtomicBoolean();

	/**
	 * Tooltips rebuild every frame while hovering, and extracting the id
	 * copies the item's whole NBT - once per stack is plenty.
	 */
	private static ItemStack lastStack;
	private static String lastId;

	private PriceTooltips() {
	}

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (!ConfigManager.get().enabled || !ConfigManager.get().priceTooltips
					|| !HypixelDetector.isOnHypixel()) {
				return;
			}

			String id = skyblockId(stack);

			if (id == null) {
				return;
			}

			ensureFresh();
			ensureNpcFresh();
			long[] price = prices.get(id);

			if (price != null) {
				lines.add(Component.literal("Bazaar: ").withStyle(ChatFormatting.DARK_GRAY)
						.append(Component.literal(Numbers.group(price[0]))
								.withStyle(ChatFormatting.GOLD))
						.append(Component.literal(" buy - ").withStyle(ChatFormatting.DARK_GRAY))
						.append(Component.literal(Numbers.group(price[1]))
								.withStyle(ChatFormatting.GOLD))
						.append(Component.literal(" sell").withStyle(ChatFormatting.DARK_GRAY)));
				npcLine(lines, id);
				return;
			}

			// Not bazaar-traded: auction gear gets its lowest BIN instead,
			// from the community price service (only the item id is sent).
			dev.skyaid.api.CoflnetApiClient.cachedLowestBin(id).ifPresent(lowest ->
					lines.add(Component.literal("Lowest BIN: ")
							.withStyle(ChatFormatting.DARK_GRAY)
							.append(Component.literal(Numbers.group(lowest) + " coins")
									.withStyle(ChatFormatting.GOLD))));
			npcLine(lines, id);

			// Museum status rides along once the background sync has data:
			// green when the museum has it, red when it is still wanted.
			// Armour speaks in sets - a lone piece cannot be donated, so its
			// line names the full set the museum actually takes.
			MuseumTracker.status(id).ifPresent(status -> {
				String neededText;

				if (status.setName() == null) {
					neededText = "Not donated";
				} else {
					// "(2/4)": how much of the set is already in hand, since
					// the museum only takes armour as a complete set.
					int have = piecesCarried(status.setPieces());
					neededText = "Not donated - " + status.setName()
							+ " set (" + have + "/" + status.setPieces().size() + ")";
				}

				var line = Component.literal("Museum: ")
						.withStyle(ChatFormatting.GRAY)
						.append(status.needed()
								? Component.literal(neededText).withStyle(ChatFormatting.RED)
								: Component.literal(status.setName() == null
												? "Donated"
												: "Donated (" + status.setName() + " set)")
										.withStyle(ChatFormatting.GREEN));

				if (status.xp() > 0) {
					line = line.append(Component.literal(
									"  +" + status.xp() + " XP")
							.withStyle(ChatFormatting.AQUA));
				}

				lines.add(line);
			});
		});
	}

	/** "NPC: 5 coins" under the market line, when the item has an NPC price. */
	private static void npcLine(java.util.List<Component> lines, String id) {
		Long npc = npcPrices.get(id);

		if (npc != null && npc > 0) {
			lines.add(Component.literal("NPC: ").withStyle(ChatFormatting.DARK_GRAY)
					.append(Component.literal(Numbers.group(npc) + " coins")
							.withStyle(ChatFormatting.YELLOW)));
		}
	}

	/**
	 * The NPC price table from the keyless items resource, refreshed daily.
	 * The response is large, so only the id -> price pairs are kept.
	 */
	private static void ensureNpcFresh() {
		long now = System.currentTimeMillis();

		if (now - npcFetchedAt < NPC_REFRESH_MILLIS
				|| now - npcAttemptAt < RETRY_FLOOR_MILLIS
				|| !npcFetching.compareAndSet(false, true)) {
			return;
		}

		npcAttemptAt = now;

		HypixelApiClient.get("/resources/skyblock/items", NPC_REFRESH_MILLIS, false)
				.thenAccept(body -> {
					try {
						body.ifPresent(json -> {
							var items = json.getAsJsonArray("items");

							if (items == null) {
								return;
							}

							Map<String, Long> table = new HashMap<>();

							for (var element : items) {
								if (!element.isJsonObject()) {
									continue;
								}

								JsonObject item = element.getAsJsonObject();

								if (item.has("id") && item.has("npc_sell_price")) {
									table.put(item.get("id").getAsString(), Math.round(
											item.get("npc_sell_price").getAsDouble()));
								}
							}

							npcPrices = table;
							npcFetchedAt = System.currentTimeMillis();
						});
					} finally {
						npcFetching.set(false);
					}
				});
	}

	/** NPC sell price of an id, if the daily table knows it. */
	public static java.util.OptionalLong npcPriceOf(String id) {
		ensureNpcFresh();
		Long npc = npcPrices.get(id);
		return npc == null ? java.util.OptionalLong.empty()
				: java.util.OptionalLong.of(npc);
	}

	/** Diagnostic view for /skyaid dump: id extraction and snapshot state. */
	public static void dumpInto(StringBuilder out, ItemStack held) {
		out.append("\nHELD ITEM:\n");

		if (held == null || held.isEmpty()) {
			out.append("  (empty hand)\n");
			return;
		}

		String id = skyblockId(held);
		out.append("  skyblock id: ").append(id == null ? "(none found)" : id).append('\n');

		CustomData data = held.get(DataComponents.CUSTOM_DATA);
		out.append("  custom data: ").append(data == null ? "(absent)"
				: data.isEmpty() ? "(empty)"
						: "keys " + data.copyTag().keySet()).append('\n');

		out.append("  bazaar snapshot: ").append(prices.size()).append(" products, ")
				.append(fetchedAt == 0 ? "never fetched"
						: (System.currentTimeMillis() - fetchedAt) / 1000 + "s old")
				.append('\n');

		if (id != null) {
			long[] price = prices.get(id);
			out.append("  listed: ").append(price == null ? "no"
					: "buy " + price[0] + ", sell " + price[1]).append('\n');
		}
	}

	/**
	 * What BUYING one of this id costs right now: bazaar insta-buy when
	 * listed, else the cached lowest BIN. For shopping-list maths.
	 */
	public static java.util.OptionalLong buyPriceOf(String id) {
		ensureFresh();
		long[] price = prices.get(id);

		if (price != null) {
			return java.util.OptionalLong.of(price[0]);
		}

		return dev.skyaid.api.CoflnetApiClient.cachedLowestBin(id);
	}

	/**
	 * What one of this id SELLS for: bazaar insta-sell else cached lowest
	 * BIN, firing a background fetch on a miss so repeat runs improve. For
	 * networth sums.
	 */
	public static java.util.OptionalLong sellValueById(String id) {
		ensureFresh();
		long[] price = prices.get(id);

		if (price != null) {
			return java.util.OptionalLong.of(price[1]);
		}

		return dev.skyaid.api.CoflnetApiClient.cachedLowestBin(id);
	}

	/**
	 * Same, but NEVER fires a network request - bulk ranking over hundreds of
	 * ids must not stampede the price service; it warms its own cache slowly.
	 */
	public static java.util.OptionalLong peekBuyPriceOf(String id) {
		ensureFresh();
		long[] price = prices.get(id);

		if (price != null) {
			return java.util.OptionalLong.of(price[0]);
		}

		return dev.skyaid.api.CoflnetApiClient.peekLowestBin(id);
	}

	/**
	 * What one of this item is worth to sell right now: bazaar insta-sell
	 * when listed there, else the cached lowest BIN. For chest-value sums.
	 */
	public static java.util.OptionalLong valueOf(ItemStack stack) {
		String id = skyblockId(stack);

		if (id == null) {
			return java.util.OptionalLong.empty();
		}

		ensureFresh();
		long[] price = prices.get(id);

		if (price != null) {
			return java.util.OptionalLong.of(price[1]);
		}

		return dev.skyaid.api.CoflnetApiClient.cachedLowestBin(id);
	}

	/** The display name of the last-hovered item, for the F1/F2 searches. */
	public static String hoveredItemName() {
		ItemStack stack = lastStack;
		return stack == null || stack.isEmpty()
				? null : stack.getHoverName().getString();
	}

	/** The Skyblock item id Hypixel wrote into the stack, or null. */
	private static String skyblockId(ItemStack stack) {
		if (stack == lastStack) {
			return lastId;
		}

		lastStack = stack;
		lastId = extractId(stack);
		lastPieceCount = -1;
		return lastId;
	}

	private static int lastPieceCount = -1;

	/**
	 * How many DISTINCT pieces of a set the player carries, inventory and
	 * worn armour included. Cached alongside the hovered stack: the scan
	 * copies NBT per slot and must not run every frame.
	 */
	private static int piecesCarried(java.util.List<String> pieceIds) {
		if (lastPieceCount >= 0) {
			return lastPieceCount;
		}

		var player = net.minecraft.client.Minecraft.getInstance().player;

		if (player == null) {
			return 0;
		}

		java.util.Set<String> found = new java.util.HashSet<>();
		var inventory = player.getInventory();

		for (int i = 0; i < inventory.getContainerSize(); i++) {
			String id = extractId(inventory.getItem(i));

			if (id != null && pieceIds.contains(id)) {
				found.add(id);
			}
		}

		lastPieceCount = found.size();
		return lastPieceCount;
	}

	/** Package-visible: MuseumTracker's deposit watcher reuses it per slot. */
	static String extractId(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);

		if (data == null || data.isEmpty()) {
			return null;
		}

		// The modern protocol UNWRAPS the 1.8 tag: "id" sits at the TOP level
		// of custom data (verified via dump: keys [id, enchantments,
		// uuid, ...]). The nested ExtraAttributes shape stays as a fallback.
		var tag = data.copyTag();

		return tag.getString("id")
				.or(() -> tag.getCompound("ExtraAttributes")
						.flatMap(extra -> extra.getString("id")))
				.orElse(null);
	}

	/** Kicks a snapshot refresh when stale; never blocks the caller. */
	private static void ensureFresh() {
		long now = System.currentTimeMillis();

		if ((now - fetchedAt < REFRESH_MILLIS && !prices.isEmpty())
				|| now - attemptAt < RETRY_FLOOR_MILLIS) {
			return;
		}

		if (!fetching.compareAndSet(false, true)) {
			return;
		}

		attemptAt = now;

		HypixelApiClient.get("/skyblock/bazaar", REFRESH_MILLIS, false)
				.whenComplete((body, error) -> {
					fetching.set(false);
					body.ifPresent(PriceTooltips::rebuild);
				});
	}

	private static void rebuild(JsonObject body) {
		JsonObject products = body.getAsJsonObject("products");

		if (products == null) {
			return;
		}

		Map<String, long[]> snapshot = new HashMap<>(products.size());

		for (String id : products.keySet()) {
			JsonObject quick = products.getAsJsonObject(id).getAsJsonObject("quick_status");

			if (quick == null) {
				continue;
			}

			snapshot.put(id, new long[]{
					Math.round(quick.get("buyPrice").getAsDouble()),
					Math.round(quick.get("sellPrice").getAsDouble())});
		}

		prices = snapshot;
		fetchedAt = System.currentTimeMillis();
	}
}
