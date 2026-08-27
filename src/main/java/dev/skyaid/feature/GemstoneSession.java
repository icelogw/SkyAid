package dev.skyaid.feature;

import dev.skyaid.core.HypixelDetector;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Session gemstone mining in the Crystal Hollows: Hypixel builds gemstone
 * clusters out of stained glass, so every broken glass block of a gem
 * colour is one swing of gemstone mining. The HUD line shows blocks mined
 * and a rough coins/hr at live bazaar prices.
 *
 * <p>The valuation is an ESTIMATE and says so: drops per block scale with
 * fortune the client cannot see, so a conservative five rough per block is
 * assumed and stated.
 */
public final class GemstoneSession {
	private static final int ROUGH_PER_BLOCK = 5;

	/** Gem-coloured glass (block and pane alike) -> the rough gem's id. */
	private static final Map<Block, String> GEMS = new HashMap<>();

	static {
		gem("ROUGH_RUBY_GEM", Blocks.STAINED_GLASS.red(),
				Blocks.STAINED_GLASS_PANE.red());
		gem("ROUGH_AMBER_GEM", Blocks.STAINED_GLASS.orange(),
				Blocks.STAINED_GLASS_PANE.orange());
		gem("ROUGH_TOPAZ_GEM", Blocks.STAINED_GLASS.yellow(),
				Blocks.STAINED_GLASS_PANE.yellow());
		gem("ROUGH_JADE_GEM", Blocks.STAINED_GLASS.lime(),
				Blocks.STAINED_GLASS_PANE.lime());
		gem("ROUGH_SAPPHIRE_GEM", Blocks.STAINED_GLASS.lightBlue(),
				Blocks.STAINED_GLASS_PANE.lightBlue());
		gem("ROUGH_AMETHYST_GEM", Blocks.STAINED_GLASS.purple(),
				Blocks.STAINED_GLASS_PANE.purple());
		gem("ROUGH_JASPER_GEM", Blocks.STAINED_GLASS.magenta(),
				Blocks.STAINED_GLASS_PANE.magenta());
		gem("ROUGH_OPAL_GEM", Blocks.STAINED_GLASS.white(),
				Blocks.STAINED_GLASS_PANE.white());
	}

	private static void gem(String id, Block glass, Block pane) {
		GEMS.put(glass, id);
		GEMS.put(pane, id);
	}

	/** Rough-gem id -> blocks broken this session. */
	private static final Map<String, Long> mined = new HashMap<>();
	private static long firstMinedAt;
	private static long totalBlocks;

	private GemstoneSession() {
	}

	public static void register() {
		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
			if (!HypixelDetector.isOnHypixel()
					|| !CrystalHollows.inCrystalHollows()) {
				return;
			}

			String gem = GEMS.get(state.getBlock());

			if (gem == null) {
				return;
			}

			synchronized (GemstoneSession.class) {
				mined.merge(gem, 1L, Long::sum);
				totalBlocks++;

				if (firstMinedAt == 0) {
					firstMinedAt = System.currentTimeMillis();
				}
			}
		});
	}

	/** "Gemstones: 1,234 mined  ~412k/hr (est.)" once mining has begun. */
	public static Optional<Component> hudLine() {
		if (totalBlocks == 0) {
			return Optional.empty();
		}

		long value = 0;

		synchronized (GemstoneSession.class) {
			for (Map.Entry<String, Long> entry : mined.entrySet()) {
				value += PriceTooltips.sellValueById(entry.getKey()).orElse(0)
						* entry.getValue() * ROUGH_PER_BLOCK;
			}
		}

		double hours = (System.currentTimeMillis() - firstMinedAt) / 3_600_000.0;
		String rate = hours > 0.02
				? "  ~" + dev.skyaid.parse.Numbers.shorten(
						Math.round(value / hours)) + "/hr (est.)"
				: "";

		return Optional.of(Component.literal("Gemstones: ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(String.format(Locale.ROOT, "%,d mined",
								totalBlocks))
						.withStyle(ChatFormatting.LIGHT_PURPLE))
				.append(Component.literal(rate).withStyle(ChatFormatting.GOLD)));
	}
}
