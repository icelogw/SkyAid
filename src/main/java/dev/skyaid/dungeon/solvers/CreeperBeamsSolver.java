package dev.skyaid.dungeon.solvers;

import dev.skyaid.dungeon.core.DungeonTracker;
import dev.skyaid.dungeon.core.MarkerRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The creeper beams puzzle: sea lanterns around a charged creeper, done by
 * standing so that pairs of lanterns line up through it. Activated by the
 * database room name; the charged creeper - the blue shimmer, a state that
 * does sync to the client - anchors the geometry. Each pair keeps its own
 * colour: WHICH two lanterns belong together is the whole answer.
 *
 * <p>Pure geometry over blocks and one entity; nothing acted on.
 */
public final class CreeperBeamsSolver implements PuzzleSolver {
	/** How far around the creeper the room's lanterns sit. */
	private static final int LANTERN_RADIUS = 20;

	/** How close a pair's line must pass to the creeper to count as a beam. */
	private static final double BEAM_TOLERANCE_SQ = 1.5 * 1.5;

	/** The room needs four beams; each pair gets its own colour. */
	private static final int[] PAIR_COLORS = {0x20E320, 0x00E5FF, 0xFFD24D, 0xFF66E0};

	private static final int SCAN_INTERVAL_TICKS = 10;

	private int tickCounter;
	private final List<BlockPos[]> pairs = new ArrayList<>();

	/**
	 * Beam target blocks never move (completed pairs swap material, but both
	 * materials are in the target set, so the POSITIONS are constant). One
	 * block sweep on entry, then only the cheap line geometry re-runs - the
	 * repeated 69k-block cube read was pure tick-thread waste.
	 */
	private final List<BlockPos> targets = new ArrayList<>();

	@Override
	public boolean handles(String roomName) {
		return roomName.equals("Creeper-Room");
	}

	@Override
	public boolean selfAnchored() {
		// A CHARGED creeper exists nowhere else in the dungeon, so this
		// solver needs no room name - waiting for identification just made
		// the puzzle "slow to load" for no reliability gain.
		return true;
	}

	@Override
	public void tick(Minecraft client, DungeonTracker.Room room) {
		// The creeper anchor is reliable on its own, so debug mode (null
		// room) may run this solver in an offline test world too.
		if (++tickCounter >= SCAN_INTERVAL_TICKS) {
			tickCounter = 0;
			solve(client, room != null);
		}

		for (int i = 0; i < pairs.size(); i++) {
			int color = PAIR_COLORS[i % PAIR_COLORS.length];
			BlockPos[] pair = pairs.get(i);

			for (BlockPos lantern : pair) {
				MarkerRenderer.boxBright(lantern, color);
			}

			Gizmos.line(centre(pair[0]), centre(pair[1]), 0xFF000000 | color)
					.persistForMillis(120)
					.setAlwaysOnTop();
		}
	}

	@Override
	public void reset() {
		pairs.clear();
		targets.clear();
		tickCounter = 0;
	}

	private void solve(Minecraft client, boolean roomIdentified) {
		pairs.clear();

		Entity creeper = findPuzzleCreeper(client, roomIdentified);

		if (creeper == null) {
			return;
		}

		Vec3 target = creeper.position().add(0, 1.0, 0);

		if (targets.isEmpty()) {
			BlockPos centre = creeper.blockPosition();

			for (BlockPos pos : BlockPos.betweenClosed(
					centre.offset(-LANTERN_RADIUS, -LANTERN_RADIUS, -LANTERN_RADIUS),
					centre.offset(LANTERN_RADIUS, LANTERN_RADIUS, LANTERN_RADIUS))) {
				// The room mixes BOTH blocks as beam endpoints, and most pairs
				// join one of each - scanning sea lanterns alone left pairs
				// half-blind, so nothing showed until a lantern-lantern pair
				// was the only one left (exactly how it failed in the field).
				var block = client.level.getBlockState(pos).getBlock();

				if (block == Blocks.SEA_LANTERN || block == Blocks.PRISMARINE) {
					targets.add(pos.immutable());
				}
			}
		}

		List<BlockPos> lanterns = targets;

		// Greedy pairing, closest-passing line first: each lantern is used
		// once, and only lines genuinely threading the creeper count.
		List<double[]> candidates = new ArrayList<>();

		for (int i = 0; i < lanterns.size(); i++) {
			for (int j = i + 1; j < lanterns.size(); j++) {
				double distSq = segmentDistanceSq(
						centre(lanterns.get(i)), centre(lanterns.get(j)), target);

				if (distSq <= BEAM_TOLERANCE_SQ) {
					candidates.add(new double[]{distSq, i, j});
				}
			}
		}

		candidates.sort(Comparator.comparingDouble(candidate -> candidate[0]));

		boolean[] used = new boolean[lanterns.size()];

		for (double[] candidate : candidates) {
			int i = (int) candidate[1];
			int j = (int) candidate[2];

			if (used[i] || used[j]) {
				continue;
			}

			used[i] = used[j] = true;
			pairs.add(new BlockPos[]{lanterns.get(i), lanterns.get(j)});
		}

		// Colour comes from list position, so the order must not depend on
		// the creeper's exact position - it bobs, the candidate distances
		// jitter, and every rescan could deal the same pairs new colours:
		// the room strobed through the palette ("goes RGB").
		// A stable key pins each pair's colour for the whole puzzle.
		pairs.sort(Comparator.comparingLong(
				pair -> Math.min(pair[0].asLong(), pair[1].asLong())));
	}

	/**
	 * The puzzle's creeper, preferring the CHARGED one - the blue shimmer,
	 * a state that does sync to the client, unlike the invulnerable flag once
	 * checked here. In an identified Creeper-Room the room name itself is
	 * proof enough: any creeper there IS the puzzle, so if the charge flag
	 * turns out not to survive Hypixel's protocol, the solver works anyway.
	 */
	private static Entity findPuzzleCreeper(Minecraft client, boolean roomIdentified) {
		Entity any = null;

		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity instanceof net.minecraft.world.entity.monster.Creeper creeper) {
				if (creeper.isPowered()) {
					return creeper;
				}

				if (any == null) {
					any = creeper;
				}
			}
		}

		return roomIdentified ? any : null;
	}

	/**
	 * Diagnostics for /skyaid dump: every creeper in reach with its synced
	 * flags, the lantern count, and how close the best candidate lines pass -
	 * enough to tell "wrong creeper assumption" from "tolerance too tight".
	 */
	public static void dumpInto(StringBuilder out) {
		var client = Minecraft.getInstance();
		out.append("\nCREEPER ROOM:\n");

		if (client.level == null || client.player == null) {
			out.append("  (not in a world)\n");
			return;
		}

		Entity anchor = null;
		int creepers = 0;

		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity instanceof net.minecraft.world.entity.monster.Creeper creeper) {
				creepers++;
				out.append("  creeper at ")
						.append(String.format("%.1f %.1f %.1f",
								creeper.position().x, creeper.position().y,
								creeper.position().z))
						.append("  powered=").append(creeper.isPowered())
						.append("  invulnerable=").append(creeper.isInvulnerable())
						.append('\n');

				if (anchor == null) {
					anchor = creeper;
				}
			}
		}

		out.append("  creepers seen: ").append(creepers).append('\n');

		if (anchor == null) {
			return;
		}

		Vec3 target = anchor.position().add(0, 1.0, 0);
		BlockPos centre = anchor.blockPosition();
		List<BlockPos> lanterns = new ArrayList<>();

		int prismarine = 0;

		for (BlockPos pos : BlockPos.betweenClosed(
				centre.offset(-LANTERN_RADIUS, -LANTERN_RADIUS, -LANTERN_RADIUS),
				centre.offset(LANTERN_RADIUS, LANTERN_RADIUS, LANTERN_RADIUS))) {
			var block = client.level.getBlockState(pos).getBlock();

			if (block == Blocks.SEA_LANTERN || block == Blocks.PRISMARINE) {
				lanterns.add(pos.immutable());

				if (block == Blocks.PRISMARINE) {
					prismarine++;
				}
			}
		}

		out.append("  beam targets within ").append(LANTERN_RADIUS).append(": ")
				.append(lanterns.size()).append(" (")
				.append(lanterns.size() - prismarine).append(" sea lantern, ")
				.append(prismarine).append(" prismarine)\n");

		List<Double> nearest = new ArrayList<>();

		for (int i = 0; i < lanterns.size(); i++) {
			for (int j = i + 1; j < lanterns.size(); j++) {
				nearest.add(Math.sqrt(segmentDistanceSq(
						centre(lanterns.get(i)), centre(lanterns.get(j)), target)));
			}
		}

		nearest.sort(Double::compareTo);
		out.append("  closest line passes (blocks from creeper, tolerance ")
				.append(Math.sqrt(BEAM_TOLERANCE_SQ)).append("): ");

		for (int i = 0; i < Math.min(6, nearest.size()); i++) {
			out.append(String.format("%.2f ", nearest.get(i)));
		}

		out.append('\n');
	}

	private static Vec3 centre(BlockPos pos) {
		return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	/** Squared distance from point {@code p} to the segment {@code a}-{@code b}. */
	private static double segmentDistanceSq(Vec3 a, Vec3 b, Vec3 p) {
		Vec3 ab = b.subtract(a);
		double lengthSq = ab.lengthSqr();

		if (lengthSq == 0) {
			return p.distanceToSqr(a);
		}

		double t = Math.max(0, Math.min(1, p.subtract(a).dot(ab) / lengthSq));
		return p.distanceToSqr(a.add(ab.scale(t)));
	}
}
