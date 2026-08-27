package dev.skyaid.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * All-time statistics, accumulated across sessions and persisted to the game
 * folder. The session tracker deliberately forgets on game close; this is the
 * ledger that never does: total active time, net coins and bits gained, and
 * how many sessions it took.
 *
 * <p>Accumulation is by checkpoint: every minute the CURRENT session's
 * counters are compared to the last look, and the difference is banked. The
 * active-time counter is the reset detector - it only ever grows within one
 * session, so a fall means the session restarted and the baseline moves
 * without banking anything. Coins are NET (spending pulls the figure down),
 * which keeps the all-time number honest rather than flattering.
 */
public final class LifetimeStats {
	private static final Path FILE =
			net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
					.resolve("skyaid-lifetime.json");

	private static final int CHECKPOINT_TICKS = 20 * 60;

	/** A session is counted once it has a real minute of activity. */
	private static final long COUNT_SESSION_AFTER_MILLIS = 60_000;

	private static long since = System.currentTimeMillis();
	private static long activeMillis;
	private static long coins;
	private static long bits;
	private static long sessions;

	private static long longestSessionMillis;
	private static long bestSessionCoins;
	private static long dungeonRuns;
	private static long bossFights;
	private static long slayerBosses;
	private static long fairySouls;
	private static long seaCreatures;
	private static long blocksMined;
	private static long rareDrops;
	private static long deaths;
	private static long nucleusRuns;

	private static long lastActive;
	private static long lastCoins;
	private static long lastBits;
	private static boolean sessionCounted;

	/** Edge detectors: each event counts on the false-to-true transition. */
	private static boolean wasInCatacombs;
	private static boolean bossWasRunning;

	private static boolean loaded;
	private static int tickCounter;
	private static int edgeCounter;

	private LifetimeStats() {
	}

	public static void register() {
		// Own slayer kills announce personally in chat ("NICE! SLAYER BOSS
		// SLAIN!", ecosystem wording). The sidebar's "Boss slain!" that was
		// counted before ALSO shows for OTHER players' bosses fought nearby
		//. Chat is personal.
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME
				.register((message, overlay) -> {
					if (overlay) {
						return;
					}

					String text = message.getString();

					if (text.contains("SLAYER BOSS SLAIN")) {
						load();
						slayerBosses++;
						save();
					}

					// "You died!" in the open world, "☠ You died and became a
					// ghost" in dungeons (ecosystem wordings). Own deaths only -
					// other players' skulls name them, not "You".
					String stripped = dev.skyaid.parse.FormatCodes.strip(text).trim();

					if (stripped.startsWith("You died")
							|| stripped.startsWith("☠ You died")) {
						load();
						deaths++;
						save();
					}
				});

		// Every block the player actually breaks, counted the moment the
		// break lands client-side. Skyblock only - a lobby or dev world
		// would pollute the ledger.
		net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents.AFTER
				.register((level, player, pos, state) -> {
					if (HypixelDetector.isOnHypixel()
							&& SkyblockTracker.state().inSkyblock()) {
						countBlockMined();
					}
				});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// The event edges are cheap boolean reads; once a second catches
			// every transition (none of these states flip faster).
			if (++edgeCounter % 20 == 0) {
				watchEvents();
			}

			if (++tickCounter >= CHECKPOINT_TICKS) {
				tickCounter = 0;
				checkpoint();
				save();
			}
		});
	}

	private static synchronized void watchEvents() {
		var state = SkyblockTracker.state();

		boolean inCatacombs = state.inCatacombs();

		if (inCatacombs && !wasInCatacombs) {
			load();
			dungeonRuns++;
			save();
		}

		wasInCatacombs = inCatacombs;

		boolean bossRunning =
				dev.skyaid.dungeon.core.DungeonTracker.bossStartMillis() > 0;

		if (bossRunning && !bossWasRunning) {
			load();
			bossFights++;
			save();
		}

		bossWasRunning = bossRunning;

	}

	/** A fairy soul claim witnessed - called by the soul markers. */
	public static synchronized void countFairySoul() {
		load();
		fairySouls++;
		save();
	}

	/** A notable sea creature announced - called by the fishing alerts. */
	public static synchronized void countSeaCreature() {
		load();
		seaCreatures++;
		save();
	}

	/** A rare-drop announcement of the player's own - called by DropTracker. */
	public static synchronized void countRareDrop() {
		load();
		rareDrops++;
		save();
	}

	/** A Crystal Nucleus completion - called by NucleusRuns. */
	public static synchronized void countNucleusRun() {
		load();
		nucleusRuns++;
		save();
	}

	/**
	 * A block the player broke. Counted live but persisted only on the
	 * minute checkpoint - mining breaks blocks far too fast for a file
	 * write per block, and a minute of tail on a crash is acceptable.
	 */
	public static synchronized void countBlockMined() {
		load();
		blocksMined++;
	}

	/** Banks the session's growth since the last look. Cheap; call freely. */
	public static synchronized void checkpoint() {
		load();
		var session = SessionTracker.snapshot();
		long active = session.activeMillis();
		long sessionCoins = session.coinsGained().orElse(0);
		long sessionBits = session.bitsGained().orElse(0);

		// Active time fell: the session restarted. New baseline, bank nothing.
		if (active < lastActive) {
			sessionCounted = false;
		} else {
			activeMillis += active - lastActive;
			coins += sessionCoins - lastCoins;
			bits += sessionBits - lastBits;

			if (!sessionCounted && active >= COUNT_SESSION_AFTER_MILLIS) {
				sessions++;
				sessionCounted = true;
			}
		}

		// The records: both only ever climb, so a plain max keeps them.
		longestSessionMillis = Math.max(longestSessionMillis, active);
		bestSessionCoins = Math.max(bestSessionCoins, sessionCoins);

		lastActive = active;
		lastCoins = sessionCoins;
		lastBits = sessionBits;
	}

	public static long sinceMillis() {
		load();
		return since;
	}

	public static long activeMillis() {
		return activeMillis;
	}

	public static long coins() {
		return coins;
	}

	public static long bits() {
		return bits;
	}

	public static long sessions() {
		return sessions;
	}

	public static long longestSessionMillis() {
		return longestSessionMillis;
	}

	public static long bestSessionCoins() {
		return bestSessionCoins;
	}

	public static long dungeonRuns() {
		return dungeonRuns;
	}

	public static long bossFights() {
		return bossFights;
	}

	public static long slayerBosses() {
		return slayerBosses;
	}

	public static long fairySouls() {
		return fairySouls;
	}

	public static long seaCreatures() {
		return seaCreatures;
	}

	public static long blocksMined() {
		return blocksMined;
	}

	public static long rareDrops() {
		return rareDrops;
	}

	public static long deaths() {
		return deaths;
	}

	public static long nucleusRuns() {
		return nucleusRuns;
	}

	/** Starts the ledger over. The old totals are gone for good. */
	public static synchronized void resetAll() {
		since = System.currentTimeMillis();
		activeMillis = 0;
		coins = 0;
		bits = 0;
		sessions = 0;
		longestSessionMillis = 0;
		bestSessionCoins = 0;
		dungeonRuns = 0;
		bossFights = 0;
		slayerBosses = 0;
		fairySouls = 0;
		seaCreatures = 0;
		blocksMined = 0;
		rareDrops = 0;
		deaths = 0;
		nucleusRuns = 0;
		sessionCounted = false;

		// The current session's already-banked part must not re-bank.
		var session = SessionTracker.snapshot();
		lastActive = session.activeMillis();
		lastCoins = session.coinsGained().orElse(0);
		lastBits = session.bitsGained().orElse(0);
		save();
	}

	private static synchronized void load() {
		if (loaded) {
			return;
		}

		loaded = true;

		try {
			if (!Files.exists(FILE)) {
				return;
			}

			JsonObject root = JsonParser.parseString(Files.readString(FILE))
					.getAsJsonObject();
			since = root.has("since") ? root.get("since").getAsLong() : since;
			activeMillis = root.has("activeMillis")
					? root.get("activeMillis").getAsLong() : 0;
			coins = root.has("coins") ? root.get("coins").getAsLong() : 0;
			bits = root.has("bits") ? root.get("bits").getAsLong() : 0;
			sessions = root.has("sessions") ? root.get("sessions").getAsLong() : 0;
			longestSessionMillis = root.has("longestSession")
					? root.get("longestSession").getAsLong() : 0;
			bestSessionCoins = root.has("bestSessionCoins")
					? root.get("bestSessionCoins").getAsLong() : 0;
			dungeonRuns = root.has("dungeonRuns")
					? root.get("dungeonRuns").getAsLong() : 0;
			bossFights = root.has("bossFights")
					? root.get("bossFights").getAsLong() : 0;
			slayerBosses = root.has("slayerBosses")
					? root.get("slayerBosses").getAsLong() : 0;
			fairySouls = root.has("fairySouls")
					? root.get("fairySouls").getAsLong() : 0;
			seaCreatures = root.has("seaCreatures")
					? root.get("seaCreatures").getAsLong() : 0;
			blocksMined = root.has("blocksMined")
					? root.get("blocksMined").getAsLong() : 0;
			rareDrops = root.has("rareDrops")
					? root.get("rareDrops").getAsLong() : 0;
			deaths = root.has("deaths") ? root.get("deaths").getAsLong() : 0;
			nucleusRuns = root.has("nucleusRuns")
					? root.get("nucleusRuns").getAsLong() : 0;
		} catch (Exception e) {
			// A broken file means the ledger starts over - noted, not fatal.
			dev.skyaid.SkyAidClient.LOGGER.warn("Could not load the lifetime stats");
		}
	}

	private static synchronized void save() {
		JsonObject root = new JsonObject();
		root.addProperty("since", since);
		root.addProperty("activeMillis", activeMillis);
		root.addProperty("coins", coins);
		root.addProperty("bits", bits);
		root.addProperty("sessions", sessions);
		root.addProperty("longestSession", longestSessionMillis);
		root.addProperty("bestSessionCoins", bestSessionCoins);
		root.addProperty("dungeonRuns", dungeonRuns);
		root.addProperty("bossFights", bossFights);
		root.addProperty("slayerBosses", slayerBosses);
		root.addProperty("fairySouls", fairySouls);
		root.addProperty("seaCreatures", seaCreatures);
		root.addProperty("blocksMined", blocksMined);
		root.addProperty("rareDrops", rareDrops);
		root.addProperty("deaths", deaths);
		root.addProperty("nucleusRuns", nucleusRuns);
		String json = root.toString();

		Thread.startVirtualThread(() -> {
			try {
				Files.writeString(FILE, json);
			} catch (Exception e) {
				dev.skyaid.SkyAidClient.LOGGER.warn("Could not save the lifetime stats");
			}
		});
	}
}
