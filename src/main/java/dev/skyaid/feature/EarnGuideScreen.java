package dev.skyaid.feature;

import com.google.gson.JsonObject;
import dev.skyaid.api.HypixelApiClient;
import dev.skyaid.parse.Bazaar;
import dev.skyaid.parse.ItemNames;
import dev.skyaid.parse.Numbers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The money-making guide as a proper menu: three tier tabs, each method with
 * its requirement, notes, and - where the output trades on the bazaar - a
 * live per-hour estimate and a button that opens the bazaar for it. Every
 * method also carries a Details button opening its long-form page: how to
 * start, the route, and the tips, with Back returning to the list.
 *
 * <p>The method list lives in a scrolling viewport with a scrollbar, so a
 * tier can hold more methods than the panel has rows. Buttons send at most
 * one user-initiated command per click, the same shape as the chat guide's
 * click-buttons.
 */
public class EarnGuideScreen extends Screen {
	private static final int WIDTH = 360;

	/**
	 * Tall enough that the price line clears the divider with padding to
	 * spare - at 46 its descenders were shaved off.
	 */
	private static final int ROW = 56;
	private static final int HEADER = 46;
	private static final int CAVEAT = 12;
	private static final int FOOTER = 40;
	private static final int VIEW_ROWS = 5;
	private static final int VIEW_HEIGHT = VIEW_ROWS * ROW;
	private static final int PANEL_HEIGHT = HEADER + CAVEAT + VIEW_HEIGHT + FOOTER;

	/** Wheel notch in pixels - about a quarter of a row. */
	private static final int SCROLL_STEP = 12;

	/** Vanilla menu palette; dark text without shadow on the light body. */
	private static final int BODY = 0xFFC6C6C6;
	private static final int LIGHT = 0xFFFFFFFF;
	private static final int DARK = 0xFF555555;
	private static final int BORDER = 0xFF000000;
	private static final int TEXT = 0xFF404040;
	private static final int MUTED = 0xFF6E6E6E;
	private static final int PRICE = 0xFF1F6B1F;
	private static final int WARN = 0xFFB00000;

	private enum Tier {
		EARLY("Early", "Early game - your first million", EarnGuide.EARLY,
				"Bazaar unlocks at Skyblock level 7 - sell to NPCs until then."),
		MID("Mid", "Mid game - roughly 1-50M", EarnGuide.MID, null),
		LATE("Late", "Late game - 50M and beyond", EarnGuide.LATE, null);

		final String tab;
		final String heading;
		final List<EarnGuide.Method> methods;
		final String caveat;

		Tier(String tab, String heading, List<EarnGuide.Method> methods, String caveat) {
			this.tab = tab;
			this.heading = heading;
			this.methods = methods;
			this.caveat = caveat;
		}
	}

	private Tier tier = Tier.EARLY;
	private volatile JsonObject products;

	/** The method whose Details page is open, or null for the list. */
	private EarnGuide.Method detail;

	private double scrollOffset;
	private final List<Button> rowButtons = new ArrayList<>();
	private final List<Integer> rowButtonBaseY = new ArrayList<>();

	public EarnGuideScreen() {
		super(Component.literal("Making money in Skyblock"));
	}

	@Override
	protected void init() {
		// The 20s cache makes repeated opens cheap; prices appear in place
		// once the answer lands - the layout never waits on the network.
		HypixelApiClient.get("/skyblock/bazaar", 20_000, false)
				.thenAccept(body -> body.ifPresent(json ->
						products = json.getAsJsonObject("products")));

		rowButtons.clear();
		rowButtonBaseY.clear();

		int x = this.width / 2 - WIDTH / 2;
		int top = panelTop();

		int tabWidth = (WIDTH - 8) / 3;
		int tabIndex = 0;

		for (Tier value : Tier.values()) {
			Button tab = Button.builder(Component.literal(value.tab + " game"),
							button -> switchTo(value))
					.bounds(x + tabIndex * (tabWidth + 4), top + 22, tabWidth, 20)
					.build();
			tab.active = tier != value || detail != null;
			addRenderableWidget(tab);
			tabIndex++;
		}

		int listTop = top + HEADER + CAVEAT;

		if (detail != null) {
			initDetail(x, top, listTop);
		} else {
			initList(x, listTop);
		}

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> close())
				.bounds(this.width / 2 - 50, top + PANEL_HEIGHT - 24, 100, 20)
				.build());

		applyScroll();
	}

	/** The list view's per-row Details and action buttons. */
	private void initList(int x, int listTop) {
		List<EarnGuide.Method> methods = tier.methods;

		for (int i = 0; i < methods.size(); i++) {
			EarnGuide.Method method = methods.get(i);
			int baseY = listTop + i * ROW + 24;

			Button more = Button.builder(Component.literal("Details"),
							button -> openDetail(method))
					.bounds(x + WIDTH - 56, baseY, 48, 18)
					.build();
			addRenderableWidget(more);
			rowButtons.add(more);
			rowButtonBaseY.add(baseY);

			Button action = actionButtonFor(method, x + WIDTH - 122, baseY);

			if (action != null) {
				addRenderableWidget(action);
				rowButtons.add(action);
				rowButtonBaseY.add(baseY);
			}
		}
	}

	/** The Details page's Back button plus the method's own action. */
	private void initDetail(int x, int top, int listTop) {
		addRenderableWidget(Button.builder(Component.literal("< Back"),
						button -> {
							detail = null;
							rebuildWidgets();
						})
				.bounds(x, top + PANEL_HEIGHT - 24, 60, 20)
				.build());

		Button action = actionButtonFor(detail, x + WIDTH - 64,
				listTop + VIEW_HEIGHT - 20);

		if (action != null) {
			addRenderableWidget(action);
		}
	}

	/** Bazaar for priced methods, Margins for the flip method, else none. */
	private Button actionButtonFor(EarnGuide.Method method, int x, int y) {
		if (method.productId() != null) {
			return Button.builder(Component.literal("Bazaar"),
							button -> openBazaar(method.productId()))
					.bounds(x, y, 62, 18)
					.build();
		}

		if (method.name().startsWith("Bazaar order flips")) {
			return Button.builder(Component.literal("Margins"),
							button -> {
								close();
								PriceCommand.lookupFlips();
							})
					.bounds(x, y, 62, 18)
					.build();
		}

		return null;
	}

	private void switchTo(Tier chosen) {
		this.tier = chosen;
		this.detail = null;
		this.scrollOffset = 0;
		rebuildWidgets();
	}

	private void openDetail(EarnGuide.Method method) {
		this.detail = method;
		rebuildWidgets();
	}

	private int contentHeight() {
		return tier.methods.size() * ROW;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - VIEW_HEIGHT);
	}

	/** Moves the row buttons with the list and hides what left the viewport. */
	private void applyScroll() {
		scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset));

		int listTop = panelTop() + HEADER + CAVEAT;
		int listBottom = listTop + VIEW_HEIGHT;

		for (int i = 0; i < rowButtons.size(); i++) {
			Button button = rowButtons.get(i);
			int y = rowButtonBaseY.get(i) - (int) scrollOffset;
			button.setY(y);
			button.visible = y >= listTop - 2 && y + 18 <= listBottom + 2;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int x = this.width / 2 - WIDTH / 2;
		int listTop = panelTop() + HEADER + CAVEAT;

		if (detail == null && mouseX >= x - 10 && mouseX <= x + WIDTH + 10
				&& mouseY >= listTop && mouseY <= listTop + VIEW_HEIGHT) {
			scrollOffset -= scrollY * SCROLL_STEP;
			applyScroll();
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** One user-initiated command per click, then back to the game. */
	private void openBazaar(String productId) {
		var client = this.minecraft;
		close();

		if (client.player != null) {
			client.player.connection.sendCommand(
					"bz " + ItemNames.cleanForSearch(Bazaar.displayName(productId)));
		}
	}

	private void close() {
		this.minecraft.setScreenAndShow(null);
	}

	private int panelTop() {
		return Math.max(4, this.height / 2 - PANEL_HEIGHT / 2);
	}

	@Override
	public void extractRenderState(
			GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		int x = this.width / 2 - WIDTH / 2;
		int top = panelTop();
		int left = x - 10;
		int right = x + WIDTH + 10;
		int bottom = top + PANEL_HEIGHT;

		// The vanilla bevelled window, matching the search screen.
		extractor.fill(left - 1, top - 1, right + 1, bottom + 1, BORDER);
		extractor.fill(left, top, right, bottom, BODY);
		extractor.fill(left, top, right, top + 2, LIGHT);
		extractor.fill(left, top, left + 2, bottom, LIGHT);
		extractor.fill(left, bottom - 2, right, bottom, DARK);
		extractor.fill(right - 2, top, right, bottom, DARK);

		super.extractRenderState(extractor, mouseX, mouseY, partialTick);

		plainCentered(extractor, tier.heading, this.width / 2, top + 8, TEXT);

		int listTop = top + HEADER + CAVEAT;

		if (detail != null) {
			extractDetail(extractor, x, listTop);
			return;
		}

		if (tier.caveat != null) {
			plainCentered(extractor, tier.caveat, this.width / 2,
					top + HEADER + 1, WARN);
		}

		List<EarnGuide.Method> methods = tier.methods;

		extractor.enableScissor(left, listTop, right, listTop + VIEW_HEIGHT);

		for (int i = 0; i < methods.size(); i++) {
			EarnGuide.Method method = methods.get(i);
			int rowTop = listTop + i * ROW - (int) scrollOffset;

			if (rowTop + ROW < listTop || rowTop > listTop + VIEW_HEIGHT) {
				continue;
			}

			// Zebra striping and an etched divider keep the rows visually
			// apart - all one grey read as a single wall of text.
			if (i % 2 == 1) {
				extractor.fill(x - 6, rowTop - 2, x + WIDTH + 6,
						rowTop + ROW - 4, 0x0F000000);
			}

			if (i > 0) {
				extractor.fill(x - 6, rowTop - 4, x + WIDTH + 6, rowTop - 3, 0xFF9A9A9A);
				extractor.fill(x - 6, rowTop - 3, x + WIDTH + 6, rowTop - 2, 0xFFE8E8E8);
			}

			extractor.text(this.font,
					clip(method.name() + "  (" + method.requirement() + ")", WIDTH - 16),
					x + 10, rowTop + 5, TEXT, false);

			String[] notes = method.notes();
			int noteWidth = method.productId() != null
					|| method.name().startsWith("Bazaar order flips")
					? WIDTH - 138 : WIDTH - 72;

			for (int line = 0; line < Math.min(2, notes.length); line++) {
				extractor.text(this.font, clip(notes[line], noteWidth),
						x + 16, rowTop + 17 + line * 10, MUTED, false);
			}

			String label = priceLabel(method);

			if (!label.isEmpty()) {
				extractor.text(this.font, label, x + 16, rowTop + 39, PRICE, false);
			}
		}

		extractor.disableScissor();

		// The scrollbar: sunken track on the panel's right, thumb sized and
		// placed by how much of the list is on screen.
		if (contentHeight() > VIEW_HEIGHT) {
			int trackLeft = right - 8;
			int trackRight = right - 4;
			extractor.fill(trackLeft, listTop, trackRight,
					listTop + VIEW_HEIGHT, 0xFF8B8B8B);

			int thumbHeight = Math.max(12,
					VIEW_HEIGHT * VIEW_HEIGHT / contentHeight());
			int thumbTop = listTop + (int) (scrollOffset
					* (VIEW_HEIGHT - thumbHeight) / maxScroll());
			extractor.fill(trackLeft, thumbTop, trackRight,
					thumbTop + thumbHeight, 0xFF555555);
			extractor.fill(trackLeft, thumbTop, trackRight - 1,
					thumbTop + thumbHeight - 1, 0xFF757575);
		}

		plainCentered(extractor,
				"Rough figures for a basic setup - prices are live.",
				this.width / 2, listTop + VIEW_HEIGHT + 4, MUTED);
	}

	/** The long-form page: name, requirement, the write-up, live price. */
	private void extractDetail(GuiGraphicsExtractor extractor, int x, int listTop) {
		plainCentered(extractor, detail.name(), this.width / 2, listTop + 2, TEXT);
		plainCentered(extractor, detail.requirement(), this.width / 2,
				listTop + 14, MUTED);

		int y = listTop + 30;

		for (String line : EarnGuide.detailsFor(detail)) {
			extractor.text(this.font, line, x + 8, y, TEXT, false);
			y += 11;
		}

		String label = priceLabel(detail);

		if (!label.isEmpty()) {
			extractor.text(this.font, label, x + 8, y + 6, PRICE, false);
		}
	}

	/** "~X/hr" from the live snapshot, or the fetching notice, or nothing. */
	private String priceLabel(EarnGuide.Method method) {
		if (method.productId() == null) {
			return "";
		}

		Optional<Double> price = EarnGuide.sellPrice(products, method.productId());

		if (price.isPresent()) {
			return "~" + Numbers.shorten(Math.round(
					method.unitsPerHour() * price.get())) + "/hr at current prices";
		}

		return products == null ? "fetching live prices..." : "";
	}

	private void plainCentered(GuiGraphicsExtractor extractor,
			String text, int centerX, int y, int colour) {
		extractor.text(this.font, text,
				centerX - this.font.width(text) / 2, y, colour, false);
	}

	private String clip(String text, int maxWidth) {
		if (this.font.width(text) <= maxWidth) {
			return text;
		}

		String out = text;

		while (out.length() > 1 && this.font.width(out + "...") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}

		return out + "...";
	}
}
