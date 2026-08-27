package dev.skyaid.dungeon;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.dungeon.core.DungeonTracker;
import dev.skyaid.dungeon.core.MarkerRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Map;
import java.util.Optional;

/**
 * The F5/M5 boss fight spawns nine Livids and only one is real. Hypixel
 * itself gives the answer away: a stained-glass block at a fixed spot in the
 * boss room is dyed the real Livid's colour, and the real one's name is
 * written in the matching chat colour. Read the glass, match the name, box
 * the boss.
 *
 * <p>The glass position and the dye-to-colour table are the community's
 * (via Skytils' LividFinder). Display only, as ever.
 */
public final class LividFinder {
	/** The tell-tale stained glass, at fixed world coordinates in the boss room. */
	private static final BlockPos GLASS = new BlockPos(13, 107, 25);

	private static final Map<DyeColor, ChatFormatting> DYE_TO_CHAT = Map.of(
			DyeColor.WHITE, ChatFormatting.WHITE,
			DyeColor.MAGENTA, ChatFormatting.LIGHT_PURPLE,
			DyeColor.RED, ChatFormatting.RED,
			DyeColor.GRAY, ChatFormatting.GRAY,
			DyeColor.GREEN, ChatFormatting.DARK_GREEN,
			DyeColor.LIME, ChatFormatting.GREEN,
			DyeColor.BLUE, ChatFormatting.BLUE,
			DyeColor.PURPLE, ChatFormatting.DARK_PURPLE,
			DyeColor.YELLOW, ChatFormatting.YELLOW);

	private static final int TICK_INTERVAL = 10;
	private static int tickCounter;
	private static Entity realLivid;

	private LividFinder() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ConfigManager.get().enabled || !ConfigManager.get().lividFinder) {
				return;
			}

			if (client.level == null || client.player == null) {
				realLivid = null;
				return;
			}

			boolean lividFloor = DungeonTracker.floor()
					.map(floor -> floor.equals("F5") || floor.equals("M5"))
					.orElse(false);

			if (!lividFloor || !SkyblockTracker.state().inCatacombs()) {
				realLivid = null;
				return;
			}

			if (++tickCounter >= TICK_INTERVAL) {
				tickCounter = 0;
				realLivid = find(client);
			}

			if (realLivid != null && realLivid.isAlive()) {
				MarkerRenderer.action(realLivid.blockPosition(), "Livid");
			}
		});
	}

	private static Entity find(net.minecraft.client.Minecraft client) {
		Block glass = client.level.getBlockState(GLASS).getBlock();
		DyeColor dye = null;

		for (DyeColor candidate : DyeColor.values()) {
			if (Blocks.STAINED_GLASS.pick(candidate) == glass) {
				dye = candidate;
				break;
			}
		}

		ChatFormatting chat = dye == null ? null : DYE_TO_CHAT.get(dye);

		if (chat == null) {
			return null;
		}

		TextColor wanted = TextColor.fromLegacyFormat(chat);

		for (Entity entity : client.level.entitiesForRendering()) {
			var name = entity.getCustomName();

			if (name == null || !name.getString().contains("Livid")) {
				continue;
			}

			// The real Livid's name carries the glass's colour on the part
			// that says "Livid"; the eight fakes wear other colours.
			boolean matches = name.visit((style, text) ->
							text.contains("Livid") && wanted.equals(style.getColor())
									? Optional.of(true) : Optional.empty(),
					net.minecraft.network.chat.Style.EMPTY).isPresent();

			if (matches) {
				return entity;
			}
		}

		return null;
	}
}
