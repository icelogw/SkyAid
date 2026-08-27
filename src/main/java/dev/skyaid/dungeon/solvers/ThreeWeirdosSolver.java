package dev.skyaid.dungeon.solvers;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.dungeon.core.DungeonTracker;
import dev.skyaid.dungeon.core.MarkerRenderer;
import dev.skyaid.parse.FormatCodes;
import dev.skyaid.parse.WeirdosPhrases;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;

/**
 * Three Weirdos: three NPCs, three chests, one truth. The truthful NPC gives
 * itself away in its chat line; its chest - the one beside its armour stand -
 * gets the action marker.
 */
final class ThreeWeirdosSolver implements PuzzleSolver {
	private String truthfulNpc;
	private BlockPos chest;

	ThreeWeirdosSolver() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !ConfigManager.get().enabled
					|| !ConfigManager.get().puzzleSolvers) {
				return;
			}

			if (DungeonTracker.currentRoom()
					.map(room -> !handles(room.name())).orElse(true)) {
				return;
			}

			String stripped = FormatCodes.strip(message.getString());

			WeirdosPhrases.truthfulNpc(stripped).ifPresent(npc -> {
				truthfulNpc = npc;
				chest = null;
			});
		});
	}

	@Override
	public boolean handles(String roomName) {
		return roomName.equals("Three-Chests");
	}

	@Override
	public void tick(Minecraft client, DungeonTracker.Room room) {
		if (truthfulNpc == null || room == null) {
			return;
		}

		if (chest == null) {
			chest = findChestBeside(client, truthfulNpc);
		}

		if (chest != null) {
			MarkerRenderer.action(chest, "Open - " + truthfulNpc);
		}
	}

	@Override
	public void reset() {
		truthfulNpc = null;
		chest = null;
	}

	/** The chest horizontally adjacent to the named NPC's armour stand. */
	private static BlockPos findChestBeside(Minecraft client, String npc) {
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity.getType() != EntityTypes.ARMOR_STAND
					|| entity.getCustomName() == null
					|| !entity.getCustomName().getString().contains(npc)) {
				continue;
			}

			BlockPos stand = entity.blockPosition();

			for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				BlockPos beside = stand.relative(direction);

				if (client.level.getBlockState(beside).getBlock() == Blocks.CHEST) {
					return beside.immutable();
				}
			}
		}

		return null;
	}
}
