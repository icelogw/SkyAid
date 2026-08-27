package dev.skyaid.dungeon.rooms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skyaid.SkyAidClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/**
 * The Dungeon Rooms Mod room database, bundled under assets/skyaid/dungeonrooms
 * (GPL-3.0, Quantizr - see NOTICE.txt there): every known room as a sorted
 * array of packed block keys ({@link dev.skyaid.parse.RoomMath#packBlock}),
 * plus every room's recorded secrets in room-relative coordinates.
 *
 * <p>Loaded once, in the background, on first use - it is ~17MB of compressed
 * block data and the game must not hitch for it. Everything here is immutable
 * after loading; {@link #ready()} says when.
 */
public final class RoomData {
	/** One recorded secret, in the room's canonical coordinates. */
	public record Secret(String name, String category, int x, int y, int z) {
	}

	private static final String BASE = "/assets/skyaid/dungeonrooms/";

	/** category folder -> room name -> sorted packed block keys. */
	private static volatile Map<String, Map<String, long[]>> rooms;

	/** room name -> its recorded secrets. */
	private static volatile Map<String, List<Secret>> secrets;

	private static volatile boolean loadStarted;
	private static volatile String loadError;

	private RoomData() {
	}

	/** Kicks off background loading; safe to call every tick. */
	public static synchronized void ensureLoading() {
		if (loadStarted) {
			return;
		}

		loadStarted = true;
		Thread loader = new Thread(RoomData::loadAll, "SkyAid-RoomData");
		loader.setDaemon(true);
		loader.start();
	}

	public static boolean ready() {
		return rooms != null && secrets != null;
	}

	/** A one-line load status for /skyaid dump. */
	public static String status() {
		if (loadError != null) {
			return "failed: " + loadError;
		}

		if (!loadStarted) {
			return "not started";
		}

		return ready() ? "loaded (" + countRooms() + " rooms)" : "loading...";
	}

	/** All rooms that could apply to a room of the given size. */
	public static Map<String, long[]> roomsForSize(String size) {
		Map<String, long[]> result = new HashMap<>();

		if (!ready()) {
			return result;
		}

		// 1x1-sized cells cover three data categories; bigger sizes are their
		// own category. Searching all of them replaces the original's use of
		// the hotbar map's room colour, at the cost of a few binary searches.
		if (size.equals("1x1")) {
			result.putAll(rooms.getOrDefault("1x1", Map.of()));
			result.putAll(rooms.getOrDefault("Puzzle", Map.of()));
			result.putAll(rooms.getOrDefault("Trap", Map.of()));
		} else {
			result.putAll(rooms.getOrDefault(size, Map.of()));
		}

		return result;
	}

	public static List<Secret> secretsFor(String roomName) {
		if (!ready()) {
			return List.of();
		}

		return secrets.getOrDefault(roomName, List.of());
	}

	private static void loadAll() {
		try {
			Map<String, Map<String, long[]>> loadedRooms = new HashMap<>();

			// index.txt lists every skeleton as "category/RoomName" - jar
			// resources cannot be listed at runtime, so the list is baked in.
			try (BufferedReader index = new BufferedReader(new InputStreamReader(
					resource("index.txt"), StandardCharsets.UTF_8))) {
				String line;

				while ((line = index.readLine()) != null) {
					// A UTF-8 BOM once rode in on the first line and broke the
					// whole load with "Missing bundled resource <BOM>1x1/...".
					line = line.replace("﻿", "").trim();

					if (line.isEmpty()) {
						continue;
					}

					int slash = line.indexOf('/');
					String category = line.substring(0, slash);
					String roomName = line.substring(slash + 1);

					loadedRooms.computeIfAbsent(category, k -> new HashMap<>())
							.put(roomName, readSkeleton(
									"catacombs/" + category + "/" + roomName + ".skeleton"));
				}
			}

			Map<String, List<Secret>> loadedSecrets = new HashMap<>();
			JsonObject root;

			try (InputStreamReader reader = new InputStreamReader(
					resource("secretlocations.json"), StandardCharsets.UTF_8)) {
				root = JsonParser.parseReader(reader).getAsJsonObject();
			}

			for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
				if (!entry.getValue().isJsonArray()) {
					continue; // copyright / license header strings
				}

				JsonArray list = entry.getValue().getAsJsonArray();
				List<Secret> roomSecrets = new java.util.ArrayList<>(list.size());

				for (JsonElement element : list) {
					JsonObject secret = element.getAsJsonObject();
					roomSecrets.add(new Secret(
							secret.get("secretName").getAsString(),
							secret.get("category").getAsString(),
							secret.get("x").getAsInt(),
							secret.get("y").getAsInt(),
							secret.get("z").getAsInt()));
				}

				loadedSecrets.put(entry.getKey(), List.copyOf(roomSecrets));
			}

			secrets = loadedSecrets;
			rooms = loadedRooms;
			SkyAidClient.LOGGER.info("SkyAid room data loaded: {} rooms, {} with secrets",
					countRooms(), loadedSecrets.size());
		} catch (Exception e) {
			loadError = e.getClass().getSimpleName() + ": " + e.getMessage();
			SkyAidClient.LOGGER.error("SkyAid room data failed to load", e);
		}
	}

	/**
	 * A .skeleton file is a deflate-compressed Java-serialized long[]. The
	 * deserialization filter admits primitive long arrays and nothing else -
	 * the data is bundled, but there is no reason to trust more than needed.
	 */
	private static long[] readSkeleton(String path) throws Exception {
		try (ObjectInputStream in = new ObjectInputStream(
				new InflaterInputStream(resource(path)))) {
			in.setObjectInputFilter(info -> {
				Class<?> type = info.serialClass();
				return type == null || type == long[].class
						? ObjectInputFilter.Status.ALLOWED
						: ObjectInputFilter.Status.REJECTED;
			});
			return (long[]) in.readObject();
		}
	}

	private static InputStream resource(String name) {
		InputStream stream = RoomData.class.getResourceAsStream(BASE + name);

		if (stream == null) {
			throw new IllegalStateException("Missing bundled resource " + name);
		}

		return stream;
	}

	private static int countRooms() {
		Map<String, Map<String, long[]>> snapshot = rooms;

		if (snapshot == null) {
			return 0;
		}

		return snapshot.values().stream().mapToInt(Map::size).sum();
	}
}
