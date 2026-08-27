package dev.skyaid.hud;

import dev.skyaid.SkyAidClient;
import dev.skyaid.config.Config;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.core.SessionTracker;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.parse.SkyblockState;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Draws the Skyblock readout: location, time, date, purse, bits and any active
 * slayer quest, subject to the per-line switches in the settings.
 *
 * <p>Only values the sidebar is already showing are rendered - this restyles and
 * repositions information the player can see, it does not reveal anything new.
 *
 * <p>A field the sidebar did not report is omitted entirely rather than drawn as
 * zero, which is why the parsers return optionals.
 */
public final class SkyblockHud {
	private static final Identifier ELEMENT_ID =
			Identifier.fromNamespaceAndPath(SkyAidClient.MOD_ID, "skyblock_hud");

	static final int LINE_HEIGHT = 10;

	/**
	 * Breathing room between the text and the edge of the backing panel. Vertical
	 * is larger than horizontal: a row of text already leaves a couple of pixels of
	 * slack either side, so equal padding reads as tight at the top and bottom.
	 */
	static final int PADDING = 3;
	static final int PADDING_Y = 6;

	/** Backing panel colour: dark and mostly transparent, like vanilla's own. */
	private static final int PANEL_COLOR = 0x90000000;

	/**
	 * Base colour for the text. Every line carries its own style, so this only
	 * shows through where a line has none.
	 */
	private static final int TEXT_COLOR = 0xFFFFFFFF;

	/** Separator rule: bright enough to read, dim enough not to compete. */
	private static final int DIVIDER_COLOR = 0x60FFFFFF;

	/** Thickness of the separator rule, in unscaled pixels. */
	private static final int RULE_HEIGHT = 1;



	private SkyblockHud() {
	}

	public static void register() {
		// addLast so the readout sits above the vanilla HUD rather than under it.
		HudElementRegistry.addLast(ELEMENT_ID, (extractor, deltaTracker) -> {
			Config config = ConfigManager.get();

			if (!config.enabled || !config.skyblockHud.visible) {
				return;
			}

			if (!HypixelDetector.isOnHypixel()) {
				return;
			}

			SkyblockState state = SkyblockTracker.state();

			if (!state.inSkyblock()) {
				return;
			}

			List<HudLine> lines = SkyblockHudLines.build(
					state, SessionTracker.snapshot(), SkyblockTracker.actionBar());

			if (lines.isEmpty()) {
				return;
			}

			draw(extractor, lines, config.skyblockHud);
		});
	}

	/**
	 * The readout's screen rectangle as {x, y, width, height}, padding and scale
	 * included.
	 *
	 * <p>One method serves the renderer and the position screen's drag target. They
	 * each measured separately once, and the two quietly disagreed - the visible box
	 * and the clickable area ended up in different places.
	 */
	static int[] measure(int guiWidth, int guiHeight, List<HudLine> lines,
			Config.HudSettings hud) {
		Font font = Minecraft.getInstance().font;
		float scale = scaleOf(hud);

		int widest = widestOf(lines, font);
		int width = Math.round(widest * scale) + PADDING * 2;
		int height = Math.round(contentHeight(lines) * scale) + PADDING_Y * 2;

		int anchorX = Math.round(hud.x * guiWidth);
		int anchorY = Math.round(hud.y * guiHeight);

		// The anchor is the right edge when right-aligned and the left edge
		// otherwise, so the padding comes off the opposite side.
		int left = (isRightAligned(hud) ? anchorX - (width - PADDING * 2) : anchorX) - PADDING;

		return new int[]{left, anchorY - PADDING_Y, width, height};
	}

	/** How tall a row is: dividers get extra space, text rows do not. */
	static int rowHeight(HudLine line) {
		return LINE_HEIGHT;
	}

	/** The widest text row; dividers count nothing. */
	private static int widestOf(List<HudLine> lines, Font font) {
		int widest = 0;

		for (HudLine line : lines) {
			if (!line.divider()) {
				widest = Math.max(widest, font.width(line.text()));
			}
		}

		return widest;
	}

	/** Total unscaled height of every row, since they are no longer uniform. */
	static int contentHeight(List<HudLine> lines) {
		int total = 0;

		for (HudLine line : lines) {
			total += rowHeight(line);
		}

		return total;
	}

	static float scaleOf(Config.HudSettings hud) {
		return Math.max(0.5f, hud.scale);
	}

	/**
	 * Past the halfway mark the readout grows leftwards from its anchor. Without
	 * this, sitting where Hypixel's sidebar does - the whole point of replacing it -
	 * would run the longer lines off screen.
	 */
	static boolean isRightAligned(Config.HudSettings hud) {
		return hud.x > 0.5f;
	}

	/**
	 * Draws the readout at the configured anchor. Shared with the position screen
	 * so what you drag is exactly what you get.
	 */
	static void draw(GuiGraphicsExtractor extractor, List<HudLine> lines,
			Config.HudSettings hud) {
		Font font = Minecraft.getInstance().font;
		float scale = scaleOf(hud);

		if (hud.background) {
			// The configured darkness; the constant's 0x90 alpha is the 56%
			// default, so untouched configs look exactly as before.
			int alpha = Math.round(
					Math.max(0, Math.min(100, hud.backgroundOpacity)) * 2.55f);
			int[] panel = measure(extractor.guiWidth(), extractor.guiHeight(), lines, hud);
			extractor.fill(panel[0], panel[1], panel[0] + panel[2], panel[1] + panel[3],
					alpha << 24);
		}

		int anchorX = Math.round(hud.x * extractor.guiWidth());
		int anchorY = Math.round(hud.y * extractor.guiHeight());

		// Scaling happens around the origin, so the anchor is divided back out to
		// keep the readout under the point it was dragged to rather than drifting
		// away from it as the scale changes.
		extractor.pose().pushMatrix();
		extractor.pose().scale(scale, scale);

		int x = Math.round(anchorX / scale);
		int y = Math.round(anchorY / scale);
		boolean rightAligned = isRightAligned(hud);

		int widest = widestOf(lines, font);

		// Rows are no longer a fixed height - dividers are taller - so the vertical
		// position is accumulated rather than derived from the index.
		int lineY = y;

		for (HudLine line : lines) {
			if (line.divider()) {
				// A rule across the readout's full width rather than a row of dashes,
				// so it lines up whatever the rows around it happen to say.
				//
				// The inset is divided by the scale because this is drawn inside the
				// scaled matrix while the backing panel is measured in screen pixels.
				// Using PADDING directly made the rule overhang the panel above scale
				// 1 and fall short below it.
				int inset = Math.round(PADDING / scale);
				int ruleY = lineY + (LINE_HEIGHT - RULE_HEIGHT) / 2;
				int left = (rightAligned ? x - widest : x) - inset;

				extractor.fill(left, ruleY, left + widest + inset * 2, ruleY + RULE_HEIGHT,
						DIVIDER_COLOR);
			} else {
				int lineX = rightAligned ? x - font.width(line.text()) : x;
				extractor.text(font, line.text(), lineX, lineY, TEXT_COLOR,
						hud.textShadow);
			}

			lineY += rowHeight(line);
		}
		extractor.pose().popMatrix();
	}
}
