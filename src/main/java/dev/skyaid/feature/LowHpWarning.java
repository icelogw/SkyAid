package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.core.SkyblockTracker;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

/**
 * A red flash around the screen edge when health drops below a quarter -
 * the action bar's number is easy to miss mid-fight, a red border is not.
 *
 * <p>Pulses with wall-clock time so it reads as urgency, not decoration.
 * Purely a drawn overlay: nothing is done ABOUT the low health.
 */
public final class LowHpWarning {
	/** Warn below this fraction of max health. */
	private static final double THRESHOLD = 0.25;

	private static final int EDGE = 10;

	private LowHpWarning() {
	}

	public static void register() {
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(dev.skyaid.SkyAidClient.MOD_ID,
						"low_hp_warning"),
				(extractor, deltaTracker) -> {
					if (!ConfigManager.get().enabled
							|| !ConfigManager.get().lowHpWarning
							|| !HypixelDetector.isOnHypixel()
							|| !SkyblockTracker.state().inSkyblock()) {
						return;
					}

					var bar = SkyblockTracker.actionBar();

					if (bar.health().isEmpty() || bar.maxHealth().isEmpty()
							|| bar.maxHealth().getAsLong() <= 0) {
						return;
					}

					double fraction = (double) bar.health().getAsLong()
							/ bar.maxHealth().getAsLong();

					if (fraction >= THRESHOLD) {
						return;
					}

					// A slow pulse between faint and strong red.
					double pulse = 0.5 + 0.5 * Math.sin(
							System.currentTimeMillis() / 250.0);
					int alpha = (int) (70 + 110 * pulse);
					int color = alpha << 24 | 0xFF2222;

					int width = extractor.guiWidth();
					int height = extractor.guiHeight();

					extractor.fill(0, 0, width, EDGE, color);
					extractor.fill(0, height - EDGE, width, height, color);
					extractor.fill(0, EDGE, EDGE, height - EDGE, color);
					extractor.fill(width - EDGE, EDGE, width, height - EDGE, color);
				});
	}
}
