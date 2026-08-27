package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Scrolling for tooltips taller than the screen. Skyblock lore runs long -
 * a maxed item's tooltip can be twice the screen height - and vanilla just
 * crops it. While a tooltip overflows, the mouse wheel moves through it:
 * the view starts at the top and scrolling down walks toward the bottom.
 *
 * <p>The position change itself happens in {@code DefaultTooltipPositionerMixin},
 * which asks {@link #reposition} every frame a tooltip is placed. This class
 * owns the offset and eats the wheel while it applies, so the menu underneath
 * never sees the scroll.
 */
public final class TooltipScroll {
	/** Pixels per wheel notch - about two lore lines. */
	private static final int SCROLL_STEP = 20;

	/** Margin kept at the screen edge, matching vanilla's own placement gap. */
	private static final int EDGE = 6;

	/**
	 * How recently a tooltip must have overflowed for the wheel to belong to
	 * it. Tooltips are placed every frame while hovered, so this stays fresh
	 * exactly as long as one is actually on screen.
	 */
	private static final long ACTIVE_WINDOW_MILLIS = 250;

	private static int offset;
	private static int maxOffset;
	private static int lastHeight;
	private static long lastOverflowAt;

	private TooltipScroll() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?>)) {
				return;
			}

			// A fresh menu means fresh tooltips: start unscrolled.
			reset();

			ScreenMouseEvents.allowMouseScroll(screen).register(
					(s, mouseX, mouseY, horizontal, vertical) -> {
						if (!scrollable() || vertical == 0) {
							return true;
						}

						// Wheel down (negative) reads further down the text.
						offset = clamp(offset
								- (int) Math.signum(vertical) * SCROLL_STEP);
						return false;
					});
		});
	}

	/**
	 * Where a tooltip should really sit, given vanilla's answer. Anything
	 * that fits on screen passes through untouched; an over-tall tooltip is
	 * pinned to the top and slid up by the accumulated scroll instead.
	 */
	public static int reposition(int screenHeight, int vanillaY, int height) {
		if (!enabled() || height + 2 * EDGE <= screenHeight) {
			return vanillaY;
		}

		// A different height is a different tooltip: back to the top.
		if (height != lastHeight) {
			lastHeight = height;
			offset = 0;
		}

		maxOffset = height + 2 * EDGE - screenHeight;
		lastOverflowAt = System.currentTimeMillis();
		offset = clamp(offset);
		return EDGE - offset;
	}

	private static boolean scrollable() {
		return enabled()
				&& System.currentTimeMillis() - lastOverflowAt < ACTIVE_WINDOW_MILLIS;
	}

	private static boolean enabled() {
		var config = ConfigManager.get();
		return config.enabled && config.tooltipScroll;
	}

	private static int clamp(int value) {
		return Math.max(0, Math.min(maxOffset, value));
	}

	private static void reset() {
		offset = 0;
		lastHeight = 0;
		lastOverflowAt = 0;
	}
}
