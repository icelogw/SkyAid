package dev.skyaid.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.skyaid.api.HypixelApiClient;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.parse.Numbers;
import dev.skyaid.parse.TimeSpans;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /skyaid auctions}: your own auctions, one line each - item and
 * quantity, the highest bid and when it was placed, and the time left.
 *
 * <p>Reads the same public Hypixel endpoint the website uses, for the player's
 * own account only. Display only, on request only - nothing is watched, nothing
 * acts, and nothing about other players' auctions is fetched.
 */
public final class AuctionsCommand {
	/** Bids move fast near an auction's end; a minute keeps repeats cheap anyway. */
	private static final long CACHE_TTL_MILLIS = 60 * 1000L;

	/** An auction item is a couple of KB of NBT; a megabyte is a generous roof. */
	private static final long MAX_ITEM_NBT_BYTES = 1_048_576;

	private AuctionsCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("auctions")
								.executes(context -> {
									run(context.getSource());
									return 1;
								}))));
	}

	private static void run(FabricClientCommandSource source) {
		if (!HypixelDetector.isOnHypixel()) {
			error(source, "Only available while connected to Hypixel.");
			return;
		}

		if (!HypixelApiClient.hasApiKey()) {
			error(source, "No Hypixel API key set. Run /skyaid key add to paste one in.");
			return;
		}

		UUID self = Minecraft.getInstance().getUser().getProfileId();
		String path = "/skyblock/auction?player=" + self.toString().replace("-", "");

		HypixelApiClient.get(path, CACHE_TTL_MILLIS, true).thenAccept(response ->
				// Back to the client thread before touching chat.
				Minecraft.getInstance().execute(() -> report(source, response)));
	}

	private static void report(FabricClientCommandSource source, Optional<JsonObject> response) {
		if (response.isEmpty()) {
			error(source, "No auction data returned (API error, or the key is invalid).");
			return;
		}

		JsonArray auctions = response.get().has("auctions")
				? response.get().getAsJsonArray("auctions")
				: new JsonArray();
		long now = System.currentTimeMillis();

		source.sendFeedback(Component.empty());

		int shown = 0;

		for (JsonElement element : auctions) {
			JsonObject auction = element.getAsJsonObject();

			// Claimed auctions are history; everything else is worth a line -
			// still running, or ended and waiting to be collected.
			if (auction.has("claimed") && auction.get("claimed").getAsBoolean()) {
				continue;
			}

			if (shown == 0) {
				source.sendFeedback(Component.literal("Your auctions:")
						.withStyle(ChatFormatting.AQUA));
			}

			source.sendFeedback(describe(auction, now));
			shown++;
		}

		if (shown == 0) {
			source.sendFeedback(Component.literal("You have no active auctions.")
					.withStyle(ChatFormatting.GRAY));
		}

		source.sendFeedback(Component.empty());
	}

	/** One line: "  Aspect of the End x1  top bid 1.3M (3m ago), ends in 2h 05m". */
	private static Component describe(JsonObject auction, long now) {
		String name = auction.has("item_name")
				? auction.get("item_name").getAsString()
				: "(unknown item)";
		int count = quantity(auction);

		long end = asLong(auction, "end");
		long highestBid = asLong(auction, "highest_bid_amount");
		long startingBid = asLong(auction, "starting_bid");
		Optional<Long> lastBidAt = lastBidTime(auction);

		StringBuilder detail = new StringBuilder("  ");

		if (highestBid > 0) {
			detail.append("top bid ").append(Numbers.group(highestBid));
			lastBidAt.ifPresent(at ->
					detail.append(" (").append(TimeSpans.brief(now - at)).append(" ago)"));
		} else {
			detail.append("no bids, starting at ").append(Numbers.group(startingBid));
		}

		if (end > now) {
			detail.append(", ends in ").append(TimeSpans.brief(end - now));
		} else {
			detail.append(highestBid > 0 ? ", sold - ready to claim" : ", expired - ready to claim");
		}

		return Component.literal("  " + name + (count > 1 ? " x" + count : ""))
				.withStyle(ChatFormatting.WHITE)
				.append(Component.literal(detail.toString()).withStyle(ChatFormatting.GRAY));
	}

	/**
	 * The quantity, dug out of the auction's item NBT. Decoration only: any
	 * failure to decode falls back to 1 rather than breaking the listing.
	 */
	private static int quantity(JsonObject auction) {
		try {
			String base64 = itemBytes(auction);

			if (base64 == null) {
				return 1;
			}

			byte[] data = Base64.getDecoder().decode(base64);

			return NbtIo.readCompressed(
							new ByteArrayInputStream(data), NbtAccounter.create(MAX_ITEM_NBT_BYTES))
					.getListOrEmpty("i")
					.getCompoundOrEmpty(0)
					.getByteOr("Count", (byte) 1);
		} catch (Exception e) {
			return 1;
		}
	}

	/** The API sends item_bytes as either a bare string or {type, data}. */
	private static String itemBytes(JsonObject auction) {
		if (!auction.has("item_bytes")) {
			return null;
		}

		JsonElement bytes = auction.get("item_bytes");

		if (bytes.isJsonPrimitive()) {
			return bytes.getAsString();
		}

		JsonObject wrapped = bytes.getAsJsonObject();
		return wrapped.has("data") ? wrapped.get("data").getAsString() : null;
	}

	/** When the newest bid landed, if there are any. */
	private static Optional<Long> lastBidTime(JsonObject auction) {
		if (!auction.has("bids")) {
			return Optional.empty();
		}

		long newest = 0;

		for (JsonElement element : auction.getAsJsonArray("bids")) {
			JsonObject bid = element.getAsJsonObject();
			newest = Math.max(newest, asLong(bid, "timestamp"));
		}

		return newest > 0 ? Optional.of(newest) : Optional.empty();
	}

	private static long asLong(JsonObject object, String member) {
		return object.has(member) && object.get(member).isJsonPrimitive()
				? object.get(member).getAsLong()
				: 0L;
	}

	private static void error(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.empty());
		source.sendError(Component.literal(message));
		source.sendFeedback(Component.empty());
	}
}
