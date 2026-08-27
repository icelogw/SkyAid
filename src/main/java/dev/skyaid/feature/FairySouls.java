package dev.skyaid.feature;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.core.TabListReader;
import dev.skyaid.parse.FormatCodes;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fairy soul markers: the NEU community's soul locations (bundled, GPL
 * lineage like the room database) drawn as soft pink boxes on the island the
 * player is on, unclaimed ones only. Claims are learned from the "SOUL!"
 * chat confirmation and remembered on disk, so the helper quiets down
 * permanently as souls are found.
 *
 * <p>Which island the player is on comes from the tab list's "Area:" line.
 * Only areas in the map below render - a new or unmapped island shows
 * nothing rather than another island's markers, and the dump prints the
 * area text needed to add it.
 */
public final class FairySouls {
	private static final int SOUL_PINK = 0xFF9BD6;
	private static final int RENDER_DISTANCE = 60;
	private static final int MAX_RENDERED = 20;

	/** Tab list "Area:" text -> the community data's island key. */
	private static final Map<String, String> AREA_TO_ISLAND = Map.ofEntries(
			Map.entry("Hub", "hub"),
			Map.entry("Spider's Den", "combat_1"),
			Map.entry("Crimson Isle", "crimson_isle"),
			Map.entry("The End", "combat_3"),
			Map.entry("The Park", "foraging_1"),
			Map.entry("The Farming Islands", "farming_1"),
			Map.entry("Gold Mine", "mining_1"),
			Map.entry("Deep Caverns", "mining_2"),
			Map.entry("Dwarven Mines", "mining_3"),
			Map.entry("Jerry's Workshop", "winter"),
			Map.entry("Dungeon Hub", "dungeon_hub"),
			Map.entry("Backwater Bayou", "fishing_1"),
			Map.entry("Galatea", "foraging_2"));

	private static final Path CLAIMED_FILE =
			net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
					.resolve("skyaid-fairy-souls.json");

	/** island key -> soul positions, from the bundled data. */
	private static Map<String, List<BlockPos>> souls;

	/** "island:x,y,z" entries already collected, persisted. */
	private static final Set<String> claimed =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static boolean claimedLoaded;

	private static volatile String lastArea = "(none)";
	private static volatile String lastIsland;
	private static int tickCounter;

	private FairySouls() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ConfigManager.get().enabled || !ConfigManager.get().fairySouls
					|| !HypixelDetector.isOnHypixel() || client.player == null) {
				return;
			}

			ensureLoaded();

			// The area lookup walks the tab list - once a second is plenty.
			if (++tickCounter % 20 == 0) {
				lastArea = currentArea();
				lastIsland = AREA_TO_ISLAND.get(lastArea);
			}

			List<BlockPos> here = lastIsland == null ? null : souls.get(lastIsland);

			if (here == null) {
				return;
			}

			var player = client.player.blockPosition();
			BlockPos nearest = null;
			double nearestDistance = Double.MAX_VALUE;
			List<BlockPos> visible = new ArrayList<>();

			for (BlockPos soul : here) {
				if (claimed.contains(key(lastIsland, soul))) {
					continue;
				}

				double distance = Math.sqrt(soul.distSqr(player));

				if (distance > RENDER_DISTANCE) {
					continue;
				}

				visible.add(soul);

				if (distance < nearestDistance) {
					nearestDistance = distance;
					nearest = soul;
				}
			}

			int drawn = 0;

			for (BlockPos soul : visible) {
				if (drawn++ >= MAX_RENDERED) {
					break;
				}

				boolean focus = soul.equals(nearest);
				Gizmos.cuboid(soul, GizmoStyle.strokeAndFill(
								(focus ? 0xFF000000 : 0x66000000) | SOUL_PINK,
								focus ? 2.0f : 1.0f,
								focus ? 0x18000000 | SOUL_PINK : 0x00000000))
						.persistForMillis(120)
						.setAlwaysOnTop();
			}
		});

		// The claim confirmation retires the nearest marker for good. The
		// wording is ecosystem knowledge, unverified against a capture - a
		// mismatch means markers linger, nothing worse.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !ConfigManager.get().fairySouls
					|| !HypixelDetector.isOnHypixel()) {
				return;
			}

			String text = FormatCodes.strip(message.getString()).trim();

			if (!text.startsWith("SOUL! You found a Fairy Soul")) {
				return;
			}

			markNearestClaimed();
		});
	}

	private static void markNearestClaimed() {
		var client = net.minecraft.client.Minecraft.getInstance();
		String island = lastIsland;
		List<BlockPos> here = island == null || souls == null ? null : souls.get(island);

		if (here == null || client.player == null) {
			return;
		}

		var player = client.player.blockPosition();
		BlockPos nearest = null;
		double nearestDistance = 10 * 10; // a claim happens at arm's length

		for (BlockPos soul : here) {
			double distanceSq = soul.distSqr(player);

			if (distanceSq < nearestDistance && !claimed.contains(key(island, soul))) {
				nearestDistance = distanceSq;
				nearest = soul;
			}
		}

		if (nearest != null) {
			claimed.add(key(island, nearest));
			saveClaimed();
			dev.skyaid.core.LifetimeStats.countFairySoul();
		}
	}

	private static String key(String island, BlockPos pos) {
		return island + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	/** The tab list's "Area: Hub" line, stripped to the area name. */
	private static String currentArea() {
		for (String line : TabListReader.lines()) {
			String stripped = FormatCodes.strip(line).trim();

			if (stripped.startsWith("Area: ")) {
				return stripped.substring("Area: ".length()).trim();
			}
		}

		return "(none)";
	}

	private static void ensureLoaded() {
		if (souls != null) {
			return;
		}

		Map<String, List<BlockPos>> loaded = new HashMap<>();

		try (var reader = new InputStreamReader(
				FairySouls.class.getResourceAsStream("/assets/skyaid/fairy_souls.json"),
				StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

			for (String island : root.keySet()) {
				if (!root.get(island).isJsonArray()) {
					continue; // the comment and Max Souls entries
				}

				List<BlockPos> positions = new ArrayList<>();

				for (JsonElement entry : root.getAsJsonArray(island)) {
					String[] parts = entry.getAsString().split(",");

					if (parts.length == 3) {
						positions.add(new BlockPos(
								Integer.parseInt(parts[0].trim()),
								Integer.parseInt(parts[1].trim()),
								Integer.parseInt(parts[2].trim())));
					}
				}

				loaded.put(island, positions);
			}
		} catch (Exception e) {
			dev.skyaid.SkyAidClient.LOGGER.warn("Could not load the fairy soul data");
		}

		souls = loaded;
		loadClaimed();
	}

	private static void loadClaimed() {
		if (claimedLoaded) {
			return;
		}

		claimedLoaded = true;

		try {
			if (Files.exists(CLAIMED_FILE)) {
				JsonParser.parseString(Files.readString(CLAIMED_FILE)).getAsJsonArray()
						.forEach(entry -> claimed.add(entry.getAsString()));
			}
		} catch (Exception e) {
			// A broken file just means markers reappear until re-claimed.
		}
	}

	private static void saveClaimed() {
		Thread.startVirtualThread(() -> {
			try {
				var array = new com.google.gson.JsonArray();
				claimed.forEach(array::add);
				Files.writeString(CLAIMED_FILE, array.toString());
			} catch (Exception e) {
				dev.skyaid.SkyAidClient.LOGGER.warn("Could not save claimed fairy souls");
			}
		});
	}

	/** For /skyaid dump: the area text is what unmapped islands need. */
	public static void dumpInto(StringBuilder out) {
		out.append("\nFAIRY SOULS:\n");
		out.append("  area: ").append(lastArea)
				.append(" -> island: ").append(lastIsland == null ? "(unmapped)" : lastIsland)
				.append('\n');
		out.append("  data: ").append(souls == null ? "(not loaded)"
				: souls.values().stream().mapToInt(List::size).sum() + " souls").append('\n');
		out.append("  claimed: ").append(claimed.size()).append('\n');
	}
}
