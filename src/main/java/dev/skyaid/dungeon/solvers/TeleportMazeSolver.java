package dev.skyaid.dungeon.solvers;

import dev.skyaid.dungeon.core.DungeonTracker;
import dev.skyaid.dungeon.core.MarkerRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The teleport maze: end-portal-frame pads, and stepping on a wrong one
 * resets you. The useful memory is which pads have already been used - this
 * marks them in dim red so the player never re-steps one, the same aid the
 * ecosystem's solvers give.
 */
final class TeleportMazeSolver implements PuzzleSolver {
	private static final int SCAN_INTERVAL_TICKS = 20;
	private static final int USED_COLOR = 0xFF5555;

	private int tickCounter;
	private final List<BlockPos> pads = new ArrayList<>();
	private final Set<BlockPos> used = new LinkedHashSet<>();

	@Override
	public boolean handles(String roomName) {
		return roomName.equals("Teleport-Pad-Room");
	}

	@Override
	public void tick(Minecraft client, DungeonTracker.Room room) {
		if (room == null) {
			return;
		}

		if (++tickCounter >= SCAN_INTERVAL_TICKS && pads.isEmpty()) {
			tickCounter = 0;
			findPads(client, room);
		}

		BlockPos player = client.player.blockPosition();

		for (BlockPos pad : pads) {
			if (player.distSqr(pad) <= 2 * 2) {
				used.add(pad);
			}
		}

		for (BlockPos pad : used) {
			MarkerRenderer.boxDim(pad, USED_COLOR);
		}
	}

	@Override
	public void reset() {
		pads.clear();
		used.clear();
		tickCounter = 0;
	}

	private void findPads(Minecraft client, DungeonTracker.Room room) {
		for (var cell : room.cells()) {
			for (int dx = 0; dx <= 30; dx++) {
				for (int dz = 0; dz <= 30; dz++) {
					for (int y = 60; y <= 80; y++) {
						BlockPos pos = new BlockPos(cell.x() + dx, y, cell.z() + dz);

						if (client.level.getBlockState(pos).getBlock()
								== Blocks.END_PORTAL_FRAME) {
							pads.add(pos.immutable());
						}
					}
				}
			}
		}
	}
}
