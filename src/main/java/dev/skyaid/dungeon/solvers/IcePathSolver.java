package dev.skyaid.dungeon.solvers;

import dev.skyaid.dungeon.core.DungeonTracker;
import dev.skyaid.dungeon.core.MarkerRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Silverfish;

/**
 * Ice Path ("Ice-Path" / "Ice-Silverfish-Room"): v1 marks the silverfish
 * itself, which the maze walls otherwise hide - knowing where it currently
 * sits is half the puzzle.
 *
 * <p>The full path solve (BFS through the ice grid to the exit) needs the
 * maze's relative geometry, which is exactly what the dump's ICE PATH
 * section captures on the first real visit: the walkable grid around the
 * silverfish, in room-relative coordinates. Built data-first on purpose -
 * the overhaul banned guessed geometry.
 */
public final class IcePathSolver implements PuzzleSolver {
	private static volatile BlockPos silverfishAt;

	@Override
	public boolean handles(String roomName) {
		return roomName.equals("Ice-Path") || roomName.equals("Ice-Silverfish-Room");
	}

	@Override
	public void tick(Minecraft client, DungeonTracker.Room room) {
		if (client.level == null || client.player == null) {
			return;
		}

		silverfishAt = null;

		for (var entity : client.level.entitiesForRendering()) {
			if (entity instanceof Silverfish fish
					&& fish.distanceTo(client.player) < 40) {
				silverfishAt = fish.blockPosition();
				MarkerRenderer.action(silverfishAt, "Silverfish");
				break;
			}
		}
	}

	@Override
	public void reset() {
		silverfishAt = null;
	}

	/** The maze grid around the silverfish - the data the BFS solve needs. */
	public static void dumpInto(StringBuilder out) {
		out.append("\nICE PATH:\n");
		BlockPos fish = silverfishAt;

		if (fish == null) {
			out.append("  (no silverfish in range)\n");
			return;
		}

		var level = Minecraft.getInstance().level;

		if (level == null) {
			return;
		}

		out.append("  silverfish at: ").append(fish.toShortString()).append('\n');

		// A 21x21 slice at the silverfish's feet: '.' walkable air,
		// '#' ice/solid, laid out north-up. This is the capture the real
		// pathfinder gets built from.
		for (int z = -10; z <= 10; z++) {
			out.append("  ");

			for (int x = -10; x <= 10; x++) {
				var state = level.getBlockState(fish.offset(x, 0, z));
				out.append(state.isAir() ? '.' : '#');
			}

			out.append('\n');
		}
	}
}
