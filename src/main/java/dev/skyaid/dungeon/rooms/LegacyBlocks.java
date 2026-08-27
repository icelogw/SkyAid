package dev.skyaid.dungeon.rooms;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.HashMap;
import java.util.Map;

/**
 * The recorded room data predates the flattening: blocks are stored as 1.8
 * numeric id times 100 plus metadata. This maps the modern blocks Hypixel
 * sends back to those ids - only for the whitelist the data was built from,
 * all deliberately rotation-invariant blocks (no stairs, no logs), because
 * rooms appear in four orientations.
 */
public final class LegacyBlocks {
	private static final Map<Block, Integer> IDS = new HashMap<>();

	static {
		IDS.put(Blocks.STONE, 100);
		IDS.put(Blocks.DIORITE, 103);
		IDS.put(Blocks.POLISHED_DIORITE, 104);
		IDS.put(Blocks.ANDESITE, 105);
		IDS.put(Blocks.POLISHED_ANDESITE, 106);
		IDS.put(Blocks.GRASS_BLOCK, 200);
		IDS.put(Blocks.DIRT, 300);
		IDS.put(Blocks.COARSE_DIRT, 301);
		IDS.put(Blocks.COBBLESTONE, 400);
		IDS.put(Blocks.BEDROCK, 700);
		IDS.put(Blocks.OAK_LEAVES, 1800);
		// 26.2 folded coloured blocks into ColorCollection holders; each
		// colour is still its own Block behind the picker methods.
		IDS.put(Blocks.WOOL.gray(), 3507);
		IDS.put(Blocks.MOSSY_COBBLESTONE, 4800);
		IDS.put(Blocks.CLAY, 8200);
		IDS.put(Blocks.STONE_BRICKS, 9800);
		IDS.put(Blocks.MOSSY_STONE_BRICKS, 9801);
		IDS.put(Blocks.CHISELED_STONE_BRICKS, 9803);
		IDS.put(Blocks.DYED_TERRACOTTA.gray(), 15907);
		IDS.put(Blocks.DYED_TERRACOTTA.cyan(), 15909);
		IDS.put(Blocks.DYED_TERRACOTTA.black(), 15915);
	}

	private LegacyBlocks() {
	}

	/**
	 * The legacy id of a block state, or -1 when the block is not part of the
	 * recorded whitelist. The old "double stone slab" (43:0) flattened into a
	 * smooth stone slab with type DOUBLE - single slabs were a different id
	 * and are not whitelisted, so only the double form maps.
	 */
	public static int idOf(BlockState state) {
		Integer id = IDS.get(state.getBlock());

		if (id != null) {
			return id;
		}

		if (state.getBlock() == Blocks.SMOOTH_STONE_SLAB
				&& state.getValueOrElse(BlockStateProperties.SLAB_TYPE,
						SlabType.BOTTOM) == SlabType.DOUBLE) {
			return 4300;
		}

		return -1;
	}
}
