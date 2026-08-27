package dev.skyaid.dungeon.solvers;

import dev.skyaid.dungeon.core.DungeonTracker;
import dev.skyaid.dungeon.core.MarkerRenderer;
import dev.skyaid.parse.BlazeTags;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;

/**
 * The blaze puzzle: ten blazes in a cage, killed strictly in health order.
 * The database identifies the room AND its variant - "Blaze-Room-1-High"
 * versus "Blaze-Room-1-Low" - so exactly ONE blaze is marked: the next kill.
 * Health is read from the name tags Hypixel itself shows above each blaze.
 *
 * <p>Display only: the box points, the player shoots.
 */
final class BlazeSolver implements PuzzleSolver {
	private record Tagged(BlockPos pos, long health) {
	}

	@Override
	public boolean handles(String roomName) {
		return roomName.startsWith("Blaze-Room");
	}

	@Override
	public void tick(Minecraft client, DungeonTracker.Room room) {
		if (room == null) {
			// No room, no variant, no kill order - nothing worth guessing.
			return;
		}

		// The variant is in the room's name. Which sense "High"/"Low" carries
		// is UNCONFIRMED until the first real blaze room: if the box marks the
		// wrong end, flip this comparison.
		boolean killHighestFirst = room.name().endsWith("-High");

		Tagged next = null;

		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity.getType() != EntityTypes.ARMOR_STAND
					|| entity.getCustomName() == null) {
				continue;
			}

			var health = BlazeTags.currentHealth(entity.getCustomName().getString());

			if (health.isEmpty()) {
				continue;
			}

			Tagged candidate = new Tagged(
					entity.blockPosition().below(1), health.getAsLong());

			if (next == null
					|| (killHighestFirst && candidate.health() > next.health())
					|| (!killHighestFirst && candidate.health() < next.health())) {
				next = candidate;
			}
		}

		if (next != null) {
			MarkerRenderer.action(next.pos(), "Shoot");
		}
	}

	@Override
	public void reset() {
	}
}
