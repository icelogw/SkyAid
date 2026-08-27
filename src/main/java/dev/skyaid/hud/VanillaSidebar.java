package dev.skyaid.hud;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.core.SkyblockTracker;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/**
 * Hides Hypixel's own sidebar when SkyAid is showing the same information, so
 * the two do not sit on screen saying the same thing twice.
 *
 * <p>Wraps the vanilla scoreboard element rather than removing it. Removal is
 * permanent and global - it would also blank the scoreboard in singleplayer and
 * on every other server, which would be a bad trade for a Hypixel mod. Wrapping
 * lets the decision be made per frame.
 *
 * <p>Nothing here reveals anything: it hides a panel and re-renders the same
 * values elsewhere. Turn the setting off and Hypixel's sidebar comes straight
 * back, unmodified.
 */
public final class VanillaSidebar {
	private VanillaSidebar() {
	}

	public static void register() {
		HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD,
				original -> (extractor, deltaTracker) -> {
					if (shouldHide()) {
						return;
					}

					original.extractRenderState(extractor, deltaTracker);
				});
	}

	/**
	 * Only hide when SkyAid is actually replacing the sidebar. If our HUD is off,
	 * or we are not in Skyblock, hiding it would leave the player with nothing.
	 */
	private static boolean shouldHide() {
		var config = ConfigManager.get();

		return config.enabled
				&& config.skyblockHud.visible
				&& config.skyblockHud.hideHypixelSidebar
				&& HypixelDetector.isOnHypixel()
				&& SkyblockTracker.state().inSkyblock();
	}
}
