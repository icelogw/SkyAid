package dev.skyaid.hud;

import dev.skyaid.config.Config;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.SessionTracker;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.parse.SkyblockState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Drag the Skyblock HUD to where you want it.
 *
 * <p>Renders the real readout through {@link SkyblockHud#draw}, so the thing you
 * drag is the thing you get - a preview built separately would drift out of step
 * the first time a line changed.
 *
 * <p>Position is stored as a fraction of the screen, so a window resize or a
 * different monitor keeps the HUD in the same relative place. Crossing the
 * halfway mark flips the readout to right-aligned, which is what lets it sit flush
 * against the right edge where Hypixel's own sidebar used to be.
 */
public class HudPositionScreen extends Screen {
	private static final int GLFW_KEY_LEFT = 263;
	private static final int GLFW_KEY_RIGHT = 262;
	private static final int GLFW_KEY_UP = 265;
	private static final int GLFW_KEY_DOWN = 264;

	/** One nudge per arrow press, as a fraction of the screen. */
	private static final float NUDGE = 0.002f;

	private final Screen parent;

	/** What is being dragged: nothing, the readout, or the dungeon map box. */
	private static final int DRAG_NONE = 0;
	private static final int DRAG_HUD = 1;
	private static final int DRAG_MAP = 2;

	private int dragging = DRAG_NONE;

	/**
	 * The box the size controls act on - whichever was clicked last. Clicking
	 * selects even when the drag does not move, so "click the thing, then
	 * resize it" works the way it reads.
	 */
	private int selected = DRAG_HUD;

	private TargetScaleSlider scaleSlider;

	/**
	 * The rectangles drawn last frame, reused for hit testing.
	 *
	 * <p>Recomputing them in the mouse handlers meant deriving them from the screen
	 * size there and the render extractor's size when drawing. Any disagreement
	 * between those two put the visible box and the clickable area in different
	 * places, so the boxes that were drawn are now the boxes stored and tested.
	 */
	private int[] hitBox;
	private int[] mapBox;

	/**
	 * The gui size the box above was measured against, so the drag maths divides by
	 * the same numbers the rendering multiplied by.
	 */
	private int guiWidth;
	private int guiHeight;

	/** Where inside the readout it was grabbed, so it does not jump on click. */
	private float grabOffsetX;
	private float grabOffsetY;

	public HudPositionScreen(Screen parent) {
		super(Component.literal("Move the HUD"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int buttonWidth = 150;
		int x = this.width / 2 - buttonWidth / 2;

		scaleSlider = new TargetScaleSlider(x, this.height - 76, buttonWidth, 20);
		addRenderableWidget(scaleSlider);

		addRenderableWidget(Button.builder(Component.literal("Reset to default"),
						button -> {
							Config.HudSettings hud = ConfigManager.get().skyblockHud;
							hud.x = Config.DEFAULT_HUD_X;
							hud.y = Config.DEFAULT_HUD_Y;
							hud.scale = 1.0f;

							Config.HudSettings map = ConfigManager.get().dungeonMap;
							map.x = 0.9965f;
							map.y = 0.006f;
							map.scale = 1.0f;

							keepOnScreen();
						})
				.bounds(x, this.height - 52, buttonWidth, 20)
				.build());

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.bounds(x, this.height - 28, buttonWidth, 20)
				.build());

		// Corrects a position saved before this clamp existed, or one saved at a
		// different window size where the box did fit.
		keepOnScreen();
	}

	/** Real values when there are any, sample text otherwise, so this works anywhere. */
	private List<HudLine> previewLines() {
		SkyblockState state = SkyblockTracker.state();
		List<HudLine> lines = SkyblockHudLines.build(
				state, SessionTracker.snapshot(), SkyblockTracker.actionBar());

		return lines.isEmpty() ? SkyblockHudLines.sample() : lines;
	}

	@Override
	public void extractRenderState(
			GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(extractor, mouseX, mouseY, partialTick);

		Config.HudSettings hud = ConfigManager.get().skyblockHud;
		List<HudLine> lines = previewLines();

		guiWidth = extractor.guiWidth();
		guiHeight = extractor.guiHeight();
		hitBox = SkyblockHud.measure(guiWidth, guiHeight, lines, hud);

		int[] box = hitBox;
		boolean hovered = contains(box, mouseX, mouseY);

		// These rectangles are the drag targets, so they are drawn exactly as they
		// are hit tested - brighter while hovered, held, or selected, so it is
		// obvious what will move and what the size control acts on.
		extractor.fill(box[0], box[1], box[0] + box[2], box[1] + box[3],
				dragging == DRAG_HUD || hovered || selected == DRAG_HUD
						? 0x70FFFFFF : 0x40FFFFFF);

		SkyblockHud.draw(extractor, lines, hud);

		// The dungeon map's box, draggable the same way. A stand-in square rather
		// than the real map, since there is rarely a dungeon map to hand in here.
		Config.HudSettings mapSettings = ConfigManager.get().dungeonMap;
		mapBox = DungeonMap.measure(mapSettings, guiWidth, guiHeight);

		boolean mapHovered = contains(mapBox, mouseX, mouseY);
		extractor.fill(mapBox[0], mapBox[1], mapBox[0] + mapBox[2], mapBox[1] + mapBox[3],
				dragging == DRAG_MAP || mapHovered || selected == DRAG_MAP
						? 0x7020A020 : 0x4020A020);
		extractor.centeredText(this.font,
				Component.literal("Dungeon map").withStyle(ChatFormatting.GREEN),
				mapBox[0] + mapBox[2] / 2, mapBox[1] + mapBox[3] / 2 - 4, 0xFF80FF80);

		extractor.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
		extractor.centeredText(this.font,
				Component.literal("Drag to move - click selects, scroll resizes")
						.withStyle(ChatFormatting.GRAY),
				this.width / 2, 26, 0xFFAAAAAA);
		extractor.centeredText(this.font,
				Component.literal("Arrow keys nudge the selected box")
						.withStyle(ChatFormatting.DARK_GRAY),
				this.width / 2, 38, 0xFF888888);

		if (ConfigManager.get().debug) {
			// The numbers that settled where the hit box actually was. Kept behind a
			// switch because "the box is not the hit box" is unanswerable by looking
			// at it - only the measurements say whether the drawn rectangle and the
			// cursor are in the same space.
			extractor.centeredText(this.font,
					Component.literal(String.format(
									"box %d,%d %dx%d | mouse %d,%d | inside %s | gui %dx%d",
									box[0], box[1], box[2], box[3], mouseX, mouseY, hovered,
									guiWidth, guiHeight))
							.withStyle(ChatFormatting.YELLOW),
					this.width / 2, 52, 0xFFFFFF55);
		}

	}

	private static boolean contains(int[] box, double x, double y) {
		return x >= box[0] && x < box[0] + box[2]
				&& y >= box[1] && y < box[1] + box[3];
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		// The map box first: it is smaller, and can overlap the readout's corner.
		if (mapBox != null && contains(mapBox, event.x(), event.y())) {
			Config.HudSettings map = ConfigManager.get().dungeonMap;
			grabOffsetX = (float) event.x() - map.x * guiWidth;
			grabOffsetY = (float) event.y() - map.y * guiHeight;
			dragging = DRAG_MAP;
			select(DRAG_MAP);
			return true;
		}

		// Only the boxes themselves are draggable. Without this check a click
		// anywhere on the screen grabbed the readout, which made the box look
		// decorative and let a stray click fling the HUD across the screen.
		if (hitBox == null || !contains(hitBox, event.x(), event.y())) {
			return false;
		}

		Config.HudSettings hud = ConfigManager.get().skyblockHud;
		grabOffsetX = (float) event.x() - hud.x * guiWidth;
		grabOffsetY = (float) event.y() - hud.y * guiHeight;
		dragging = DRAG_HUD;
		select(DRAG_HUD);
		return true;
	}

	/** Scrolling over a box selects it and resizes it, a notch at a time. */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
			return true;
		}

		if (scrollY == 0) {
			return false;
		}

		if (mapBox != null && contains(mapBox, mouseX, mouseY)) {
			select(DRAG_MAP);
		} else if (hitBox != null && contains(hitBox, mouseX, mouseY)) {
			select(DRAG_HUD);
		} else {
			return false;
		}

		Config.HudSettings settings = selectedSettings();
		settings.scale = Math.max(0.5f, Math.min(2.0f,
				settings.scale + 0.05f * (float) Math.signum(scrollY)));
		settings.scale = Math.round(settings.scale * 100.0f) / 100.0f;

		if (selected == DRAG_HUD) {
			keepOnScreen();
		}

		scaleSlider.refresh();
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging == DRAG_NONE) {
			return super.mouseDragged(event, dragX, dragY);
		}

		if (dragging == DRAG_MAP) {
			Config.HudSettings map = ConfigManager.get().dungeonMap;
			map.x = clamp((float) (event.x() - grabOffsetX) / this.width);
			map.y = clamp((float) (event.y() - grabOffsetY) / this.height);
			return true;
		}

		Config.HudSettings hud = ConfigManager.get().skyblockHud;
		hud.x = clamp((float) (event.x() - grabOffsetX) / this.width);
		hud.y = clamp((float) (event.y() - grabOffsetY) / this.height);
		keepOnScreen();
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = DRAG_NONE;
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		Config.HudSettings settings = selectedSettings();

		switch (event.key()) {
			case GLFW_KEY_LEFT -> settings.x = clamp(settings.x - NUDGE);
			case GLFW_KEY_RIGHT -> settings.x = clamp(settings.x + NUDGE);
			case GLFW_KEY_UP -> settings.y = clamp(settings.y - NUDGE);
			case GLFW_KEY_DOWN -> settings.y = clamp(settings.y + NUDGE);
			default -> {
				return super.keyPressed(event);
			}
		}

		if (selected == DRAG_HUD) {
			keepOnScreen();
		}

		return true;
	}

	private static float clamp(float value) {
		return Math.max(0.0f, Math.min(1.0f, value));
	}

	/**
	 * Pulls the readout back until the whole box is on screen.
	 *
	 * <p>Clamping the anchor to 0..1 was not enough: the anchor is one corner, so at
	 * x = 1 the text and its padding still hung off the right edge, leaving part of
	 * the readout unreadable and unclickable. This clamps the box instead, which
	 * means the limit depends on how wide the text currently is.
	 */
	private void keepOnScreen() {
		Config.HudSettings hud = ConfigManager.get().skyblockHud;
		List<HudLine> lines = previewLines();

		Font font = Minecraft.getInstance().font;
		float scale = SkyblockHud.scaleOf(hud);

		int widest = 0;

		for (HudLine line : lines) {
			if (!line.divider()) {
				widest = Math.max(widest, font.width(line.text()));
			}
		}

		int textWidth = Math.round(widest * scale);
		int textHeight = Math.round(SkyblockHud.contentHeight(lines) * scale);

		// The anchor is the right edge when right-aligned and the left edge
		// otherwise, so the room either side of it swaps over with the alignment.
		boolean rightAligned = SkyblockHud.isRightAligned(hud);
		// Horizontal and vertical padding differ, so the clamp has to use each on its
		// own axis or the panel edge overhangs the screen by the difference.
		int padX = SkyblockHud.PADDING;
		int padY = SkyblockHud.PADDING_Y;
		int minX = rightAligned ? textWidth + padX : padX;
		int maxX = rightAligned ? this.width - padX : this.width - textWidth - padX;

		hud.x = fit(hud.x * this.width, minX, maxX) / this.width;
		hud.y = fit(hud.y * this.height, padY, this.height - textHeight - padY) / this.height;
	}
	/** Clamps into a range, tolerating a box larger than the space available. */
	private static float fit(float value, int min, int max) {
		if (max < min) {
			return min;
		}

		return Math.max(min, Math.min(max, value));
	}

	@Override
	public void onClose() {
		ConfigManager.save();
		this.minecraft.setScreenAndShow(parent);
	}

	/** The settings of whatever is selected: the readout, or the map box. */
	private Config.HudSettings selectedSettings() {
		return selected == DRAG_MAP
				? ConfigManager.get().dungeonMap
				: ConfigManager.get().skyblockHud;
	}

	private void select(int target) {
		selected = target;
		scaleSlider.refresh();
	}

	/**
	 * One size control for whichever box is selected, half size to double. The
	 * config keeps a plain multiplier, which is what the renderers want and what
	 * reads sensibly if the file is edited by hand.
	 */
	private class TargetScaleSlider extends AbstractSliderButton {
		private static final float MIN = 0.5f;
		private static final float MAX = 2.0f;

		TargetScaleSlider(int x, int y, int width, int height) {
			super(x, y, width, height, Component.empty(), 0);
			refresh();
		}

		/** Re-reads the selected box's scale, after a selection change or scroll. */
		void refresh() {
			this.value = toSlider(selectedSettings().scale);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(String.format(Locale.ROOT, "%s size: %.2fx",
					selected == DRAG_MAP ? "Map" : "HUD", fromSlider(this.value))));
		}

		@Override
		protected void applyValue() {
			selectedSettings().scale = fromSlider(this.value);

			if (selected == DRAG_HUD) {
				keepOnScreen();
			}
		}

		private static double toSlider(float scale) {
			return (Math.max(MIN, Math.min(MAX, scale)) - MIN) / (MAX - MIN);
		}

		private static float fromSlider(double slider) {
			// Rounded to hundredths so dragging cannot leave an unreadable
			// 1.0374x in the config file.
			return Math.round((MIN + (MAX - MIN) * (float) slider) * 100.0f) / 100.0f;
		}
	}
}
