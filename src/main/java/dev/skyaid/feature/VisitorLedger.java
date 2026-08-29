package dev.skyaid.feature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skyaid.config.ConfigManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A ledger of what Garden visitors actually cost: when a priced offer's
 * required items leave the inventory shortly after the menu, that offer
 * counts as served at its live-priced cost. Totals persist across sessions;
 * {@code /skyaid visitors} reads them back.
 *
 * <p>Purely observational - acceptance is inferred from the items leaving,
 * the same watching-not-acting shape as the museum deposit tracker.
 */
public final class VisitorLedger {
	/** How long after the menu an acceptance can still be recognised. */
	private static final long WINDOW_MILLIS = 60_000;

	private static final int CHECK_INTERVAL_TICKS = 20;

	private record Offer(long cost, Map<String, Long> needed, long at) {
	}

	private static Offer pending;
	private static Map<String, Long> baseline;
	private static int tickCounter;

	private static long served;
	private static long coinsSpent;
	private static long sessionServed;
	private static long sessionSpent;
	private static boolean loaded;

	private VisitorLedger() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (pending == null || client.player == null) {
				return;
			}

			if (System.currentTimeMillis() - pending.at() > WINDOW_MILLIS) {
				pending = null;
				baseline = null;
				return;
			}

			if (++tickCounter < CHECK_INTERVAL_TICKS) {
				return;
			}

			tickCounter = 0;
			Map<String, Long> current = countInventory(pending.needed().keySet());
			boolean allGone = true;

			for (Map.Entry<String, Long> need : pending.needed().entrySet()) {
				long before = baseline.getOrDefault(need.getKey(), 0L);
				long now = current.getOrDefault(need.getKey(), 0L);

				if (before - now < need.getValue()) {
					allGone = false;
					break;
				}
			}

			if (allGone) {
				load();
				served++;
				coinsSpent += pending.cost();
				sessionServed++;
				sessionSpent += pending.cost();
				dev.skyaid.core.EventLog.event("visitors",
						"offer ACCEPTED: ~" + pending.cost());
				save();
				pending = null;
				baseline = null;
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("visitors").executes(context -> {
							report();
							return 1;
						}))));
	}

	/**
	 * Called by VisitorCost once a visitor menu has been priced: the cost
	 * and the required item names/amounts to watch for.
	 */
	public static void noteOffer(long cost, List<String[]> wanted) {
		if (!ConfigManager.get().visitorLedger || wanted.isEmpty()) {
			return;
		}

		Map<String, Long> needed = new HashMap<>();

		for (String[] item : wanted) {
			try {
				needed.merge(item[0], Long.parseLong(item[1]), Long::sum);
			} catch (NumberFormatException e) {
				return; // A count that does not parse poisons the whole match.
			}
		}

		dev.skyaid.core.EventLog.event("visitors",
				"offer noted: ~" + cost + " for " + needed);
		pending = new Offer(cost, needed, System.currentTimeMillis());
		baseline = countInventory(needed.keySet());
		tickCounter = 0;
	}

	/** Current inventory counts of the given display names. */
	private static Map<String, Long> countInventory(java.util.Set<String> names) {
		Map<String, Long> counts = new HashMap<>();
		var player = Minecraft.getInstance().player;

		if (player == null) {
			return counts;
		}

		var inventory = player.getInventory();

		for (int i = 0; i < inventory.getContainerSize(); i++) {
			var stack = inventory.getItem(i);

			if (stack.isEmpty()) {
				continue;
			}

			String name = stack.getHoverName().getString().trim();

			if (names.contains(name)) {
				counts.merge(name, (long) stack.getCount(), Long::sum);
			}
		}

		return counts;
	}

	private static void report() {
		load();
		var chat = Minecraft.getInstance().gui.hud.getChat();
		chat.addClientSystemMessage(Component.empty());
		chat.addClientSystemMessage(Component.literal("Visitor ledger")
				.withStyle(ChatFormatting.AQUA));
		chat.addClientSystemMessage(line("This session", sessionServed, sessionSpent));
		chat.addClientSystemMessage(line("Lifetime", served, coinsSpent));
		chat.addClientSystemMessage(Component.literal(
						"  Costs are the live-priced estimate at accept time.")
				.withStyle(ChatFormatting.DARK_GRAY));
		chat.addClientSystemMessage(Component.empty());
	}

	private static Component line(String label, long count, long spent) {
		return Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(count + " served").withStyle(ChatFormatting.GREEN))
				.append(Component.literal("  ~" + dev.skyaid.parse.Numbers.shorten(spent)
						+ " spent").withStyle(ChatFormatting.GOLD));
	}

	/** Lifetime visitors served, for the Stats tab. */
	public static long lifetimeServed() {
		load();
		return served;
	}

	/** Lifetime coins spent on visitors (priced estimates), for the Stats tab. */
	public static long lifetimeSpent() {
		load();
		return coinsSpent;
	}

	private static Path file() {
		return FabricLoader.getInstance().getGameDir()
				.resolve("skyaid-visitors.json");
	}

	private static void load() {
		if (loaded) {
			return;
		}

		loaded = true;

		try {
			if (Files.exists(file())) {
				JsonObject root = JsonParser.parseString(
						Files.readString(file())).getAsJsonObject();
				served = root.has("served") ? root.get("served").getAsLong() : 0;
				coinsSpent = root.has("coinsSpent")
						? root.get("coinsSpent").getAsLong() : 0;
			}
		} catch (Exception e) {
			dev.skyaid.SkyAidClient.LOGGER.warn("Could not read the visitor ledger");
		}
	}

	private static void save() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("served", served);
			root.addProperty("coinsSpent", coinsSpent);
			Files.writeString(file(), root.toString());
		} catch (Exception e) {
			dev.skyaid.SkyAidClient.LOGGER.warn("Could not save the visitor ledger");
		}
	}
}
