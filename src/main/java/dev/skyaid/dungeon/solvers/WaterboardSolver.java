package dev.skyaid.dungeon.solvers;

import dev.skyaid.dungeon.core.DungeonTracker;
import dev.skyaid.dungeon.core.MarkerRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * The waterboard puzzle: six levers, each toggling every block of one material
 * on the board, routing a water stream into five latching targets. Marks, as
 * the action instruction, exactly the levers whose current state differs from
 * the known solution for the current target.
 *
 * <p>Activated by the database room name ("Water Puzzle"). Solutions are the
 * community's verified lever tables (via Skytils' WaterBoardSolver, itself
 * from Danker's mod, GPL/AGPL lineage). Two pieces of ground truth replace
 * earlier guesswork, both field-verified against a real room dump:
 * <ul>
 *   <li>The board VARIANT is named by the two materials at Y 77-78 within one
 *       block of a piston head at Y 82 - the anchor is what separates the
 *       variant pair from the board's own blocks at the same height.</li>
 *   <li>The CURRENT TARGET is physical: a row of coloured wool at Y 56 holds
 *       one block per uncompleted colour; done colours retract. No timers,
 *       no assumed order - the room says what remains.</li>
 * </ul>
 * Lever state is read live from the blocks, so a mis-flick simply shows up as
 * a marker.
 */
public final class WaterboardSolver implements PuzzleSolver {
	private enum Lever {
		QUARTZ(Blocks.QUARTZ_BLOCK),
		GOLD(Blocks.GOLD_BLOCK),
		COAL(Blocks.COAL_BLOCK),
		DIAMOND(Blocks.DIAMOND_BLOCK),
		EMERALD(Blocks.EMERALD_BLOCK),
		CLAY(Blocks.TERRACOTTA);

		final Block mount;

		Lever(Block mount) {
			this.mount = mount;
		}
	}

	/** Target colours, index-aligned with the solution tables and the wool. */
	private static final String[] TARGETS = {"Purple", "Orange", "Blue", "Green", "Red"};

	private static final Block[] TARGET_WOOL = {
			Blocks.WOOL.purple(), Blocks.WOOL.orange(), Blocks.WOOL.blue(),
			Blocks.WOOL.green(), Blocks.WOOL.red()};

	/** solutions[variant][target] = levers that must be ON; the rest stay OFF. */
	@SuppressWarnings("unchecked")
	private static final EnumSet<Lever>[][] SOLUTIONS = new EnumSet[][]{
			{ // Variant 0: gold + clay in the stream.
					EnumSet.of(Lever.QUARTZ, Lever.GOLD, Lever.DIAMOND, Lever.CLAY),
					EnumSet.of(Lever.GOLD, Lever.COAL, Lever.EMERALD),
					EnumSet.of(Lever.QUARTZ, Lever.GOLD, Lever.EMERALD, Lever.CLAY),
					EnumSet.of(Lever.EMERALD),
					EnumSet.noneOf(Lever.class)},
			{ // Variant 1: emerald + quartz.
					EnumSet.of(Lever.COAL),
					EnumSet.of(Lever.QUARTZ, Lever.GOLD, Lever.EMERALD, Lever.CLAY),
					EnumSet.of(Lever.QUARTZ, Lever.DIAMOND, Lever.EMERALD),
					EnumSet.of(Lever.QUARTZ, Lever.EMERALD),
					EnumSet.of(Lever.QUARTZ, Lever.COAL, Lever.EMERALD)},
			{ // Variant 2: quartz + diamond.
					EnumSet.of(Lever.QUARTZ, Lever.GOLD, Lever.DIAMOND),
					EnumSet.of(Lever.EMERALD),
					EnumSet.of(Lever.QUARTZ, Lever.DIAMOND),
					EnumSet.noneOf(Lever.class),
					EnumSet.of(Lever.GOLD, Lever.EMERALD)},
			{ // Variant 3: gold + quartz.
					EnumSet.of(Lever.QUARTZ, Lever.GOLD, Lever.EMERALD, Lever.CLAY),
					EnumSet.of(Lever.GOLD, Lever.COAL),
					EnumSet.of(Lever.QUARTZ, Lever.GOLD, Lever.COAL, Lever.EMERALD, Lever.CLAY),
					EnumSet.of(Lever.GOLD, Lever.EMERALD),
					EnumSet.of(Lever.GOLD, Lever.DIAMOND, Lever.EMERALD, Lever.CLAY)}};

	/**
	 * The variant pair sits at world Y 77-78 within one block of a PISTON
	 * HEAD at Y 82 - Skytils' anchor, which is what disambiguates it from the
	 * board's own toggleable blocks at the same height. Absolute world Y is
	 * identical between the 1.8-era reference and the modern client
	 *.
	 */
	private static final int PISTON_HEAD_Y = 82;
	private static final int PAIR_Y_MIN = 77;
	private static final int PAIR_Y_MAX = 78;

	/** The wool row of still-open targets sits at chest height. */
	private static final int WOOL_ROW_Y = 56;

	/** Horizontal reach around the player; Y is the dungeon band instead. */
	private static final int SCAN_RADIUS = 24;

	/** Rooms sit in this world-Y band; levers ~61, board top ~93. */
	private static final int SCAN_Y_MIN = 55;
	private static final int SCAN_Y_MAX = 100;

	private static final int SCAN_INTERVAL_TICKS = 5;

	private static int tickCounter;
	private static String currentTarget = "";
	private static final Map<Lever, BlockPos> toFlick = new EnumMap<>(Lever.class);

	/**
	 * The room's geometry never moves: levers, the variant, and the wool row
	 * positions are discovered by ONE full scan on entry, then every pass just
	 * re-reads a dozen known blocks. The full 110k-block sweep used to run
	 * four times a second on the tick thread - most of the room's stutter.
	 */
	private static final Map<Lever, BlockPos> leverPositions = new EnumMap<>(Lever.class);
	private static final BlockPos[] woolPositions = new BlockPos[TARGETS.length];
	private static int cachedVariant = -1;
	private static boolean discovered;
	private static int discoverPacer;

	@Override
	public boolean handles(String roomName) {
		return roomName.equals("Water Puzzle");
	}

	@Override
	public void tick(Minecraft client, DungeonTracker.Room room) {
		if (room == null) {
			return; // No offline stand-in exists for this room's geometry.
		}

		if (++tickCounter >= SCAN_INTERVAL_TICKS) {
			tickCounter = 0;
			solve(client);
		}

		for (BlockPos pos : toFlick.values()) {
			MarkerRenderer.action(pos, "Flick - " + currentTarget);
		}
	}

	@Override
	public void reset() {
		toFlick.clear();
		currentTarget = "";
		tickCounter = 0;
		leverPositions.clear();
		java.util.Arrays.fill(woolPositions, null);
		cachedVariant = -1;
		discovered = false;
	}

	private static void solve(Minecraft client) {
		toFlick.clear();
		currentTarget = "";

		// Discovery retries are paced: a room whose chunks or variant refuse
		// to resolve must not re-run the full sweep four times a second.
		if (!discovered) {
			if (++discoverPacer < 8) {
				return;
			}

			discoverPacer = 0;
			discover(client);
		}

		if (cachedVariant < 0 || leverPositions.isEmpty()) {
			return;
		}

		// The first still-extended wool names the target being worked on;
		// none left means the puzzle is solved and the markers stand down.
		int target = -1;

		for (int i = 0; i < woolPositions.length; i++) {
			if (woolPositions[i] != null && client.level
					.getBlockState(woolPositions[i]).getBlock() == TARGET_WOOL[i]) {
				target = i;
				break;
			}
		}

		if (target < 0) {
			return;
		}

		currentTarget = TARGETS[target];
		EnumSet<Lever> required = SOLUTIONS[cachedVariant][target];

		for (Map.Entry<Lever, BlockPos> entry : leverPositions.entrySet()) {
			var state = client.level.getBlockState(entry.getValue());
			boolean isOn = state.getValueOrElse(BlockStateProperties.POWERED, false);
			boolean shouldBeOn = required.contains(entry.getKey());

			if (shouldBeOn != isOn) {
				toFlick.put(entry.getKey(), entry.getValue());
			}
		}
	}

	/** The one full sweep: finds levers, variant, and the wool row. */
	private static void discover(Minecraft client) {
		BlockPos centre = client.player.blockPosition();
		List<BlockPos> pistonHeads = new ArrayList<>();
		List<Map.Entry<Lever, BlockPos>> pairCandidates = new ArrayList<>();

		for (BlockPos pos : BlockPos.betweenClosed(
				new BlockPos(centre.getX() - SCAN_RADIUS, SCAN_Y_MIN,
						centre.getZ() - SCAN_RADIUS),
				new BlockPos(centre.getX() + SCAN_RADIUS, SCAN_Y_MAX,
						centre.getZ() + SCAN_RADIUS))) {
			var state = client.level.getBlockState(pos);
			Block block = state.getBlock();

			if (block == Blocks.LEVER) {
				// The room mounts each lever on a block of the material it
				// toggles; the mount is what names the lever.
				for (Lever lever : Lever.values()) {
					if (touchesMount(client, pos, lever.mount)) {
						leverPositions.put(lever, pos.immutable());
						break;
					}
				}

				continue;
			}

			if (block == Blocks.PISTON_HEAD && pos.getY() == PISTON_HEAD_Y) {
				pistonHeads.add(pos.immutable());
				continue;
			}

			if (pos.getY() >= PAIR_Y_MIN && pos.getY() <= PAIR_Y_MAX) {
				for (Lever lever : Lever.values()) {
					if (block == lever.mount) {
						pairCandidates.add(Map.entry(lever, pos.immutable()));
						break;
					}
				}

				continue;
			}

			if (pos.getY() == WOOL_ROW_Y) {
				for (int i = 0; i < TARGET_WOOL.length; i++) {
					if (block == TARGET_WOOL[i] && woolPositions[i] == null) {
						woolPositions[i] = pos.immutable();
					}
				}
			}
		}

		cachedVariant = variantAt(pistonHeads, pairCandidates);

		int woolFound = 0;

		for (BlockPos wool : woolPositions) {
			if (wool != null) {
				woolFound++;
			}
		}

		// Discovery counts once everything essential answered - INCLUDING at
		// least one wool target. Field bug: standing by the levers, the sweep
		// resolved variant and levers with the wool row outside its box and
		// declared itself done, leaving "target (none)" for the whole run
		// while the dump's own fresh scan showed the wool fine. A fully
		// solved room keeps re-sweeping every ~2s; harmless and brief.
		discovered = cachedVariant >= 0 && leverPositions.size() >= 5 && woolFound > 0;
	}

	/** Whether one of the six neighbouring blocks is the lever's mount. */
	private static boolean touchesMount(
			Minecraft client, BlockPos lever, Block mount) {
		for (var direction : net.minecraft.core.Direction.values()) {
			if (client.level.getBlockState(lever.relative(direction)).getBlock() == mount) {
				return true;
			}
		}

		return false;
	}

	/**
	 * The variant from the two materials within one block of a piston head.
	 * Demands EXACTLY two distinct materials there - guessing wrong flicks
	 * wrong levers, so ambiguity stays quiet instead.
	 */
	private static int variantAt(List<BlockPos> pistonHeads,
			List<Map.Entry<Lever, BlockPos>> candidates) {
		for (BlockPos head : pistonHeads) {
			EnumSet<Lever> pair = EnumSet.noneOf(Lever.class);

			for (Map.Entry<Lever, BlockPos> candidate : candidates) {
				BlockPos pos = candidate.getValue();

				if (Math.abs(pos.getX() - head.getX()) <= 1
						&& Math.abs(pos.getZ() - head.getZ()) <= 1) {
					pair.add(candidate.getKey());
				}
			}

			if (pair.size() != 2) {
				continue;
			}

			if (pair.contains(Lever.GOLD) && pair.contains(Lever.CLAY)) {
				return 0;
			}

			if (pair.contains(Lever.EMERALD) && pair.contains(Lever.QUARTZ)) {
				return 1;
			}

			if (pair.contains(Lever.QUARTZ) && pair.contains(Lever.DIAMOND)) {
				return 2;
			}

			if (pair.contains(Lever.GOLD) && pair.contains(Lever.QUARTZ)) {
				return 3;
			}
		}

		return -1;
	}

	/**
	 * Appends a diagnostic view of the room to a /skyaid dump: levers, piston
	 * heads with the materials beside each, the wool target row, and the
	 * resolved variant - everything the solver decides from.
	 */
	public static void dumpInto(StringBuilder out) {
		var client = Minecraft.getInstance();
		out.append("\nWATERBOARD:\n");

		if (client.level == null || client.player == null) {
			out.append("  (not in a world)\n");
			return;
		}

		BlockPos centre = client.player.blockPosition();
		List<BlockPos> pistonHeads = new ArrayList<>();
		List<Map.Entry<Lever, BlockPos>> pairCandidates = new ArrayList<>();
		boolean[] woolPresent = new boolean[TARGETS.length];
		int leversFound = 0;

		for (BlockPos pos : BlockPos.betweenClosed(
				new BlockPos(centre.getX() - SCAN_RADIUS, SCAN_Y_MIN,
						centre.getZ() - SCAN_RADIUS),
				new BlockPos(centre.getX() + SCAN_RADIUS, SCAN_Y_MAX,
						centre.getZ() + SCAN_RADIUS))) {
			var state = client.level.getBlockState(pos);
			Block block = state.getBlock();

			if (block == Blocks.LEVER) {
				for (Lever lever : Lever.values()) {
					if (touchesMount(client, pos, lever.mount)) {
						leversFound++;
						out.append("  lever ").append(lever)
								.append(state.getValueOrElse(
										BlockStateProperties.POWERED, false)
										? "  ON" : "  off")
								.append('\n');
						break;
					}
				}

				continue;
			}

			if (block == Blocks.PISTON_HEAD && pos.getY() == PISTON_HEAD_Y) {
				pistonHeads.add(pos.immutable());
				continue;
			}

			if (pos.getY() >= PAIR_Y_MIN && pos.getY() <= PAIR_Y_MAX) {
				for (Lever lever : Lever.values()) {
					if (block == lever.mount) {
						pairCandidates.add(Map.entry(lever, pos.immutable()));
						break;
					}
				}

				continue;
			}

			if (pos.getY() == WOOL_ROW_Y) {
				for (int i = 0; i < TARGET_WOOL.length; i++) {
					if (block == TARGET_WOOL[i]) {
						woolPresent[i] = true;
					}
				}
			}
		}

		out.append("  levers found: ").append(leversFound).append('\n');
		out.append("  piston heads @y").append(PISTON_HEAD_Y).append(": ")
				.append(pistonHeads.size()).append('\n');

		for (BlockPos head : pistonHeads) {
			out.append("    head ").append(head.getX()).append(' ')
					.append(head.getZ()).append(" beside:");

			for (Map.Entry<Lever, BlockPos> candidate : pairCandidates) {
				BlockPos pos = candidate.getValue();

				if (Math.abs(pos.getX() - head.getX()) <= 1
						&& Math.abs(pos.getZ() - head.getZ()) <= 1) {
					out.append(' ').append(candidate.getKey());
				}
			}

			out.append('\n');
		}

		out.append("  wool row:");

		for (int i = 0; i < TARGETS.length; i++) {
			out.append(' ').append(TARGETS[i]).append(woolPresent[i] ? "+" : "-");
		}

		out.append('\n');

		// The SOLVER's cached view, distinct from this method's fresh scan -
		// a real failure was exactly the two disagreeing (fresh scan saw the
		// wool, the cache held none).
		int woolCached = 0;

		for (BlockPos wool : woolPositions) {
			if (wool != null) {
				woolCached++;
			}
		}

		out.append("  variant ").append(variantAt(pistonHeads, pairCandidates))
				.append(", target ").append(currentTarget.isEmpty() ? "(none)" : currentTarget)
				.append(", to flick ").append(toFlick.keySet()).append('\n');
		out.append("  solver cache: discovered ").append(discovered)
				.append(", variant ").append(cachedVariant)
				.append(", levers ").append(leverPositions.size())
				.append(", wool ").append(woolCached).append('\n');
	}
}
