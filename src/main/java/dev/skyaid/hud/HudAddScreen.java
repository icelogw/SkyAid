package dev.skyaid.hud;

import dev.skyaid.parse.HudLayout;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the HUD can draw, as an add menu: a divider, always, and every
 * element - the ones already in the arrangement shown greyed out, the removed
 * ones clickable to bring back. Showing the full roster rather than only the
 * removed entries is deliberate: a list of five leaves you wondering where the
 * rest went.
 *
 * <p>The roster outgrew the screen, so the element grid scrolls: the Divider
 * and Cancel stay pinned, the wheel moves everything between them, and a
 * scrollbar shows where you are.
 */
public class HudAddScreen extends Screen {
	private static final int COLUMN_WIDTH = 130;
	private static final int GAP = 4;
	private static final int ROW = 24;
	private static final int TOP = 44;

	/** Room kept clear for the pinned Cancel button. */
	private static final int FOOTER = 36;

	private static final int SCROLL_STEP = 12;

	private final HudArrangeScreen parent;

	private double scrollOffset;
	private final List<Button> rowButtons = new ArrayList<>();
	private final List<Integer> rowButtonBaseY = new ArrayList<>();

	public HudAddScreen(HudArrangeScreen parent) {
		super(Component.literal("Add to this layout"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rowButtons.clear();
		rowButtonBaseY.clear();

		int fullWidth = COLUMN_WIDTH * 2 + GAP;
		int left = this.width / 2 - fullWidth / 2;

		addRenderableWidget(Button.builder(Component.literal("Divider"),
						button -> {
							parent.addDivider();
							onClose();
						})
				.bounds(left, TOP, fullWidth, 20)
				.build());

		var elements = HudLayout.ELEMENTS;
		int listTop = listTop();

		for (int i = 0; i < elements.size(); i++) {
			String id = elements.get(i);
			int x = left + (i % 2) * (COLUMN_WIDTH + GAP);
			int baseY = listTop + (i / 2) * ROW;

			Button button = Button.builder(
							Component.literal(HudArrangeScreen.displayName(id)),
							pressed -> {
								parent.restore(id);
								onClose();
							})
					.bounds(x, baseY, COLUMN_WIDTH, 20)
					.build();

			// Already in the arrangement: shown so the roster reads complete,
			// disabled because it cannot be added twice.
			button.active = !parent.hasElement(id);
			addRenderableWidget(button);
			rowButtons.add(button);
			rowButtonBaseY.add(baseY);
		}

		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
				.bounds(left, this.height - FOOTER + 8, fullWidth, 20)
				.build());

		applyScroll();
	}

	private int listTop() {
		return TOP + 28;
	}

	private int viewportHeight() {
		return Math.max(ROW, this.height - FOOTER - listTop());
	}

	private int contentHeight() {
		return (HudLayout.ELEMENTS.size() + 1) / 2 * ROW;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - viewportHeight());
	}

	/** Moves the grid with the wheel and hides what left the viewport. */
	private void applyScroll() {
		scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset));

		int listTop = listTop();
		int listBottom = listTop + viewportHeight();

		for (int i = 0; i < rowButtons.size(); i++) {
			Button button = rowButtons.get(i);
			int y = rowButtonBaseY.get(i) - (int) scrollOffset;
			button.setY(y);
			button.visible = y >= listTop - 2 && y + 20 <= listBottom + 4;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scrollOffset -= scrollY * SCROLL_STEP;
		applyScroll();
		return true;
	}

	@Override
	public void extractRenderState(
			GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(extractor, mouseX, mouseY, partialTick);

		extractor.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
		extractor.centeredText(this.font,
				Component.literal("Greyed-out lines are already in the layout")
						.withStyle(ChatFormatting.DARK_GRAY),
				this.width / 2, 27, 0xFF888888);

		if (maxScroll() > 0) {
			int trackLeft = this.width / 2 + COLUMN_WIDTH + GAP / 2 + 6;
			int listTop = listTop();
			int viewport = viewportHeight();

			extractor.fill(trackLeft, listTop, trackLeft + 4,
					listTop + viewport, 0xFF2B2B2B);

			int thumbHeight = Math.max(12, viewport * viewport / contentHeight());
			int thumbTop = listTop + (int) (scrollOffset
					* (viewport - thumbHeight) / maxScroll());
			extractor.fill(trackLeft, thumbTop, trackLeft + 4,
					thumbTop + thumbHeight, 0xFF9E9E9E);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.setScreenAndShow(parent);
	}
}
