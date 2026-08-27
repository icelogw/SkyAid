package dev.skyaid.hud;

import dev.skyaid.config.Config;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.parse.BossBars;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hides banner-style boss bars - the ones Hypixel uses as a text strip rather
 * than for a boss - while leaving real encounter bars alone.
 *
 * <p>Two kinds are recognised: hypixel.net adverts, hidden whenever the filter
 * is on, and the zone-quest Objective banner, hidden only while SkyAid's own
 * HUD is showing the objective in its place - the same "never hide something
 * and leave nothing" rule as {@link VanillaSidebar}. Unrecognised bars always
 * render, so an actual boss can never be filtered by mistake.
 *
 * <p>The vanilla overlay renders its whole map in one call, so the banners are
 * lifted out just for the extraction and put back straight after. Both happen
 * on the render thread within one frame; nothing else can see the gap.
 */
public final class BossBarFilter {
	private BossBarFilter() {
	}

	public static void register() {
		HudElementRegistry.replaceElement(VanillaHudElements.BOSS_BAR,
				original -> (extractor, deltaTracker) -> {
					if (!shouldFilter()) {
						original.extractRenderState(extractor, deltaTracker);
						return;
					}

					Map<UUID, LerpingBossEvent> events =
							Minecraft.getInstance().gui.hud.getBossOverlay().events;
					Map<UUID, LerpingBossEvent> all = new LinkedHashMap<>(events);

					events.values().removeIf(event -> hides(event.getName().getString()));

					try {
						original.extractRenderState(extractor, deltaTracker);
					} finally {
						// Restore from the copy rather than re-adding the removed
						// entries, so the bars keep their on-screen order.
						events.clear();
						events.putAll(all);
					}
				});
	}

	private static boolean shouldFilter() {
		Config config = ConfigManager.get();

		return config.enabled
				&& config.skyblockHud.hideBannerBossBars
				&& HypixelDetector.isOnHypixel();
	}

	private static boolean hides(String barName) {
		if (BossBars.isAdvert(barName)) {
			return true;
		}

		return BossBars.isObjective(barName) && replacingObjective();
	}

	/** Only hide the Objective banner while SkyAid is showing it elsewhere. */
	private static boolean replacingObjective() {
		Config.HudSettings hud = ConfigManager.get().skyblockHud;

		return hud.visible
				&& hud.elements.objective
				&& SkyblockTracker.state().inSkyblock();
	}
}
