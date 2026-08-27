package dev.skyaid.feature;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * SkyAid's replacement for Hypixel's sign-based market search: a proper text
 * box with search history and item-name autofill. The sign never shows - this
 * screen takes its place the moment it opens, and submitting writes the query
 * into the hidden sign and closes it, which is exactly what the sign's own
 * Done button does. Cancelling closes the sign untouched.
 */
public class MarketSearchScreen extends Screen {
	private static final int WIDTH = 220;
	private static final int ROW_HEIGHT = 14;
	private static final int MAX_ROWS = 8;

	private final AbstractSignEditScreen sign;
	private final boolean bazaar;

	private EditBox query;
	private List<String> rows = List.of();

	public MarketSearchScreen(AbstractSignEditScreen sign, boolean bazaar) {
		super(Component.literal(bazaar
				? "Search the Bazaar" : "Search the Auction House"));
		this.sign = sign;
		this.bazaar = bazaar;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDTH / 2;
		int y = this.height / 2 - 60;

		query = new EditBox(this.font, x, y, WIDTH, 20, Component.literal("Search"));
		query.setMaxLength(40);
		query.setHint(Component.literal("Item name..."));
		query.setResponder(text -> rows = SignSearchAssist.suggestionsFor(text, bazaar));
		addRenderableWidget(query);

		addRenderableWidget(Button.builder(Component.literal("Search"),
						button -> submit(query.getValue()))
				.bounds(x, y + 24 + MAX_ROWS * ROW_HEIGHT + 8, WIDTH / 2 - 2, 20)
				.build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL,
						button -> onClose())
				.bounds(x + WIDTH / 2 + 2, y + 24 + MAX_ROWS * ROW_HEIGHT + 8,
						WIDTH / 2 - 2, 20)
				.build());

		rows = SignSearchAssist.suggestionsFor("", bazaar);

		if (bazaar) {
			SignSearchAssist.bazaarNames(); // starts the fetch before typing begins
		}
		setInitialFocus(query);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
		if (key.key() == 257 || key.key() == 335) { // enter submits
			submit(query.getValue());
			return true;
		}

		return super.keyPressed(key);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent mouse, boolean doubled) {
		int x = this.width / 2 - WIDTH / 2;
		int top = this.height / 2 - 60 + 24;

		if (mouse.x() >= x && mouse.x() < x + WIDTH
				&& mouse.y() >= top && mouse.y() < top + rows.size() * ROW_HEIGHT) {
			int index = (int) ((mouse.y() - top) / ROW_HEIGHT);

			if (index >= 0 && index < rows.size()) {
				submit(rows.get(index));
				return true;
			}
		}

		return super.mouseClicked(mouse, doubled);
	}

	private void submit(String text) {
		String trimmed = text == null ? "" : text.trim();

		if (trimmed.isEmpty()) {
			onClose();
			return;
		}

		SignSearchAssist.submitSearch(sign, trimmed, bazaar);
		this.minecraft.setScreenAndShow(null);
	}

	@Override
	public void extractRenderState(
			GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		int x = this.width / 2 - WIDTH / 2;
		int y = this.height / 2 - 60;
		int listTop = y + 24;
		int listBottom = listTop + MAX_ROWS * ROW_HEIGHT;

		// The vanilla bevelled window: black outline, light top/left bevel,
		// dark bottom/right bevel, the familiar grey body.
		int left = x - 10;
		int top = y - 32;
		int right = x + WIDTH + 10;
		int bottom = listBottom + 42;

		extractor.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF000000);
		extractor.fill(left, top, right, bottom, 0xFFC6C6C6);
		extractor.fill(left, top, right, top + 2, 0xFFFFFFFF);
		extractor.fill(left, top, left + 2, bottom, 0xFFFFFFFF);
		extractor.fill(left, bottom - 2, right, bottom, 0xFF555555);
		extractor.fill(right - 2, top, right, bottom, 0xFF555555);

		// The suggestion list sits in a sunken slot-style inset: dark edge
		// up-left, light edge down-right, the mid grey slot body.
		int insetLeft = x - 4;
		int insetRight = x + WIDTH + 4;
		extractor.fill(insetLeft - 1, listTop - 3, insetRight + 1, listBottom + 3, 0xFF373737);
		extractor.fill(insetLeft, listTop - 2, insetRight + 2, listBottom + 4, 0xFFFFFFFF);
		extractor.fill(insetLeft, listTop - 2, insetRight + 1, listBottom + 3, 0xFF8B8B8B);

		super.extractRenderState(extractor, mouseX, mouseY, partialTick);

		// Centred title on the light body: dark text, NO shadow.
		String heading = this.title.getString();
		extractor.text(this.font, heading,
				this.width / 2 - this.font.width(heading) / 2, top + 9, 0xFF404040, false);

		if (rows.isEmpty()) {
			String hint = "Type an item name...";
			extractor.text(this.font, hint,
					this.width / 2 - this.font.width(hint) / 2,
					listTop + (MAX_ROWS * ROW_HEIGHT) / 2 - 4, 0xFFCCCCCC, true);
		}

		for (int i = 0; i < rows.size(); i++) {
			int rowY = listTop + i * ROW_HEIGHT;
			boolean hovered = mouseX >= x && mouseX < x + WIDTH
					&& mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

			// Subtle striping keeps long lists readable; hover outshines it.
			if (i % 2 == 1) {
				extractor.fill(insetLeft, rowY, insetRight + 1, rowY + ROW_HEIGHT, 0x18000000);
			}

			if (hovered) {
				extractor.fill(insetLeft, rowY, insetRight + 1, rowY + ROW_HEIGHT, 0x80FFFFFF);
			}

			// Bright text WITH shadow on the dark slot body; history rows in
			// gold with a quiet right-aligned tag, item completions in white.
			boolean fromHistory = SignSearchAssist.isHistory(rows.get(i));
			extractor.text(this.font, rows.get(i), x + 2, rowY + 3,
					fromHistory ? 0xFFFFE080 : 0xFFFFFFFF, true);

			if (fromHistory) {
				String tag = "recent";
				extractor.text(this.font, tag,
						x + WIDTH - this.font.width(tag) - 2, rowY + 3, 0xFFAAAAAA, true);
			}
		}
	}

	/** Closing without a search still answers the hidden sign, untouched. */
	@Override
	public void onClose() {
		SignSearchAssist.cancelSign(sign);
		this.minecraft.setScreenAndShow(null);
	}
}
