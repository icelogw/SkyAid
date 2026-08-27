package dev.skyaid.dungeon.core;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.dungeon.rooms.LegacyBlocks;
import dev.skyaid.dungeon.rooms.RoomData;
import dev.skyaid.parse.RoomMath;
import dev.skyaid.parse.RoomMath.Cell;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single source of dungeon truth: whether the player is in the Catacombs,
 * which floor, and which recorded room they are standing in. Everything
 * dungeon-facing asks this class; nothing else scans for room identity.
 *
 * <p>Identification is elimination against the bundled room database: sample
 * whitelisted blocks in the room, convert each into the recorded coordinate
 * space in every still-possible rotation, and drop every recorded room that
 * lacks it. One room left, with confirmation to spare, is the answer. Solved
 * rooms are cached for the run, so walking back in is instant.
 */
public final class DungeonTracker {
	/** An identified room: its database name, rotation, and world anchors. */
	public record Room(String name, String direction, Cell corner, List<Cell> cells) {
	}

	private static final int TICK_INTERVAL = 10;

	/** Samples needed beyond the last elimination before a match is trusted. */
	private static final int CONFIRM_SAMPLES = 10;

	/** Identification attempts before giving this room a long rest. */
	private static final int MAX_ATTEMPTS = 10;

	/**
	 * How many samples a room may MISS before it is dropped. Hypixel drifts:
	 * a block changed since the data was recorded must cost a strike, not a
	 * full restart - hard elimination turned one stale block into repeated
	 * five-second penalty loops, felt in game as solvers "slow to load".
	 */
	private static final int MISS_TOLERANCE = 3;

	private static int tickCounter;
	private static long tickClock;

	/** The cell-set key of the room the player is standing in. */
	private static String currentKey = "";
	private static List<Cell> currentCells = List.of();
	private static String currentSize = "undefined";

	/** Elimination state: direction -> candidate room -> missed samples. */
	private static Map<String, Map<String, Integer>> possible;
	private static int confirmations;
	private static int attempts;
	private static long cooldownUntil;

	private static Room current;

	/** Rooms solved this run, keyed by their cell set. */
	private static final Map<String, Room> solved = new HashMap<>();

	private DungeonTracker() {
	}

	/** The identified room the player is in, if identification has landed. */
	public static Optional<Room> currentRoom() {
		return Optional.ofNullable(current);
	}

	/** The room's cell key - changes whenever the player crosses rooms. */
	public static String currentKey() {
		return currentKey;
	}

	/** The dungeon floor tag ("E", "F3", "M5") when in the Catacombs. */
	public static Optional<String> floor() {
		return SkyblockTracker.state().dungeonFloor();
	}

	/** Wall-clock millis when the boss fight began, or 0 outside one. */
	private static volatile long bossStartMillis;

	public static long bossStartMillis() {
		return bossStartMillis;
	}

	/**
	 * F7's boss is really four fights (Maxor, Storm, Goldor, Necron) and each
	 * announces itself with its own "[BOSS] <Name>:" dialogue - a new speaker
	 * means a new phase. Single-boss floors just show one phase, same as the
	 * plain clock.
	 */
	private static volatile String bossSpeaker;
	private static volatile long phaseStartMillis;

	/** The current phase's name and its start, or empty before any dialogue. */
	public static Optional<String> bossPhase() {
		return Optional.ofNullable(bossSpeaker);
	}

	public static long phaseStartMillis() {
		return phaseStartMillis;
	}

	public static void register() {
		// Every dungeon boss opens with "[BOSS]" dialogue; the first such
		// line starts the fight clock the HUD shows, and each new speaker
		// starts a phase clock.
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME
				.register((message, overlay) -> {
					if (overlay || !SkyblockTracker.state().inCatacombs()) {
						return;
					}

					String text = message.getString();

					if (!text.startsWith("[BOSS]")) {
						return;
					}

					if (bossStartMillis == 0) {
						bossStartMillis = System.currentTimeMillis();
					}

					int colon = text.indexOf(':');

					if (colon > "[BOSS] ".length()) {
						String speaker = text.substring("[BOSS]".length(), colon).trim();

						if (!speaker.isEmpty() && !speaker.equals(bossSpeaker)) {
							bossSpeaker = speaker;
							phaseStartMillis = System.currentTimeMillis();
						}
					}
				});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ConfigManager.get().enabled) {
				return;
			}

			if (client.level == null || client.player == null) {
				return;
			}

			if (!SkyblockTracker.state().inCatacombs() && !ConfigManager.get().debug) {
				if (!currentKey.isEmpty() || !solved.isEmpty()) {
					resetRoom();
					solved.clear();
				}

				bossStartMillis = 0;
				bossSpeaker = null;
				phaseStartMillis = 0;
				return;
			}

			tickClock++;

			if (++tickCounter < TICK_INTERVAL) {
				return;
			}

			tickCounter = 0;
			RoomData.ensureLoading();

			if (!RoomData.ready()) {
				return;
			}

			updateRoom(client);

			if (current == null && tickClock >= cooldownUntil
					&& attempts < MAX_ATTEMPTS && !currentCells.isEmpty()) {
				attemptIdentification(client);
			}
		});
	}

	private static Cell lastPlayerCell;

	/** Re-keys the state when the player walks into a different room. */
	private static void updateRoom(Minecraft client) {
		Cell playerCell = RoomMath.cellAt(
				client.player.position().x, client.player.position().z);

		// An identified room's segments are settled: no reason to re-run the
		// gap-probing BFS every pass while the player stays in the same cell.
		if (playerCell.equals(lastPlayerCell) && current != null) {
			return;
		}

		lastPlayerCell = playerCell;
		List<Cell> cells = segmentsOf(client, playerCell);
		String key = cells.toString();

		if (key.equals(currentKey)) {
			return;
		}

		resetRoom();
		currentKey = key;
		currentCells = cells;
		currentSize = RoomMath.size(cells);
		current = solved.get(key);
	}

	private static void resetRoom() {
		currentKey = "";
		currentCells = List.of();
		currentSize = "undefined";
		possible = null;
		confirmations = 0;
		attempts = 0;
		cooldownUntil = 0;
		current = null;
	}

	/**
	 * The room's cells, found physically: distinct rooms are islands in the
	 * void, so solid floor crossing the one-block gap between cells - outside
	 * the doorway strip, which bridges SEPARATE rooms - means one room spans
	 * both.
	 */
	private static List<Cell> segmentsOf(Minecraft client, Cell start) {
		List<Cell> cells = new ArrayList<>();
		ArrayDeque<Cell> frontier = new ArrayDeque<>();
		cells.add(start);
		frontier.add(start);

		while (!frontier.isEmpty() && cells.size() < 4) {
			Cell cell = frontier.poll();

			for (int[] step : new int[][]{{32, 0}, {-32, 0}, {0, 32}, {0, -32}}) {
				Cell next = new Cell(cell.x() + step[0], cell.z() + step[1]);

				if (!cells.contains(next) && gapIsSolid(client, cell, next)) {
					cells.add(next);
					frontier.add(next);
				}
			}
		}

		return cells;
	}

	/** Whether the gap line between two adjacent cells carries solid floor. */
	private static boolean gapIsSolid(Minecraft client, Cell from, Cell to) {
		int solid = 0;

		for (int along : new int[]{4, 7, 10, 20, 23, 26}) {
			for (int y = 68; y <= 71; y++) {
				BlockPos pos;

				if (to.x() != from.x()) {
					int gapX = Math.max(from.x(), to.x()) - 1;
					pos = new BlockPos(gapX, y, from.z() + along);
				} else {
					int gapZ = Math.max(from.z(), to.z()) - 1;
					pos = new BlockPos(from.x() + along, y, gapZ);
				}

				if (!client.level.getBlockState(pos).isAir()) {
					solid++;
				}
			}
		}

		return solid >= 6;
	}

	/** One round of sampling and elimination; may conclude with a match. */
	private static void attemptIdentification(Minecraft client) {
		List<String> directions = possible != null
				? new ArrayList<>(possible.keySet())
				: RoomMath.possibleDirections(currentSize, currentCells);

		if (directions.isEmpty()) {
			attempts = MAX_ATTEMPTS;
			return;
		}

		if (possible == null) {
			Map<String, long[]> candidates = RoomData.roomsForSize(currentSize);
			possible = new LinkedHashMap<>();

			for (String direction : directions) {
				Map<String, Integer> misses = new LinkedHashMap<>();

				for (String room : candidates.keySet()) {
					misses.put(room, 0);
				}

				possible.put(direction, misses);
			}

			confirmations = 0;
		}

		Map<String, long[]> data = RoomData.roomsForSize(currentSize);
		Map<String, Cell> corners = new HashMap<>();

		for (String direction : directions) {
			corners.put(direction, RoomMath.cornerFor(direction, currentCells));
		}

		List<long[]> samples = collectSamples(client);

		if (samples.size() < 12) {
			// Chunks still arriving; try again shortly without spending an attempt.
			cooldownUntil = tickClock + 20;
			return;
		}

		for (long[] sample : samples) {
			int remaining = 0;

			for (String direction : directions) {
				Map<String, Integer> rooms = possible.get(direction);

				if (rooms.isEmpty()) {
					continue;
				}

				int[] relative = RoomMath.actualToRelative(
						(int) sample[0], (int) sample[1], (int) sample[2],
						direction, corners.get(direction));
				long key = RoomMath.packBlock(
						relative[0], relative[1], relative[2], (int) sample[3]);

				rooms.entrySet().removeIf(entry -> {
					if (Arrays.binarySearch(data.get(entry.getKey()), key) < 0) {
						entry.setValue(entry.getValue() + 1);
					}

					return entry.getValue() >= MISS_TOLERANCE;
				});
				remaining += rooms.size();
			}

			if (remaining == 0) {
				// Even with strike tolerance, everything fell: bad samples or
				// missing data. Start the elimination over on a later attempt.
				possible = null;
				attempts++;
				cooldownUntil = tickClock + 40;
				return;
			}

			if (remaining == 1) {
				confirmations++;

				if (confirmations >= CONFIRM_SAMPLES) {
					for (String direction : directions) {
						for (String room : possible.get(direction).keySet()) {
							Room found = new Room(room, direction,
									corners.get(direction), currentCells);
							solved.put(currentKey, found);
							current = found;
							return;
						}
					}
				}
			}
		}

		attempts++;
		cooldownUntil = tickClock + 20;
	}

	/**
	 * Whitelisted blocks from the room's cells as {x, y, z, legacyId} rows -
	 * at most one per column, so the samples spread across the whole room
	 * instead of clustering where stone is thickest. The offsets shift with
	 * each attempt so a retry sees different blocks.
	 */
	private static List<long[]> collectSamples(Minecraft client) {
		List<long[]> samples = new ArrayList<>();
		int offset = attempts % 3;

		for (Cell cell : currentCells) {
			for (int dx = 1 + offset; dx <= 29; dx += 4) {
				for (int dz = 1 + offset; dz <= 29; dz += 4) {
					int x = cell.x() + dx;
					int z = cell.z() + dz;
					int yStart = 60 + ((dx * 7 + dz * 13 + offset * 5) % 9);

					for (int y = yStart; y <= 100; y += 3) {
						if (RoomMath.isDoorway(x, y, z)) {
							continue;
						}

						int id = LegacyBlocks.idOf(
								client.level.getBlockState(new BlockPos(x, y, z)));

						if (id >= 0) {
							samples.add(new long[]{x, y, z, id});
							break;
						}
					}

					if (samples.size() >= 40) {
						return samples;
					}
				}
			}
		}

		return samples;
	}

	/** Identification state for /skyaid dump. */
	public static void dumpInto(StringBuilder out) {
		out.append("\nROOM DETECTION:\n");
		out.append("  data:      ").append(RoomData.status()).append('\n');
		out.append("  floor:     ").append(floor().orElse("(none)")).append('\n');
		out.append("  cells:     ").append(currentKey.isEmpty() ? "(none)" : currentKey)
				.append("  size ").append(currentSize).append('\n');

		if (current != null) {
			out.append("  room:      ").append(current.name())
					.append("  facing ").append(current.direction())
					.append("  corner ").append(current.corner()).append('\n');
		} else {
			out.append("  room:      unidentified, attempts ").append(attempts)
					.append('/').append(MAX_ATTEMPTS).append('\n');

			if (possible != null) {
				for (Map.Entry<String, Map<String, Integer>> entry : possible.entrySet()) {
					out.append("    ").append(entry.getKey()).append(": ")
							.append(entry.getValue().size()).append(" candidates");

					if (entry.getValue().size() <= 4) {
						out.append(' ').append(entry.getValue().keySet());
					}

					out.append('\n');
				}
			}
		}
	}
}
