package dev.skyaid.feature;

import dev.skyaid.parse.Numbers;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The museum browser: a searchable, sortable, paged grid of everything the
 * museum still wants - the user's design, drawn in the classic menu style.
 * Each tile is an item icon with its headline number; hovering shows the full
 * story (wing, cost, XP, coins per XP).
 *
 * <p>Icons are best-effort vanilla stand-ins picked from the entry's name -
 * Hypixel's real textures live server-side, and a sword-shaped guess beats a
 * grid of identical books.
 */
public class MuseumBrowserScreen extends Screen {
	private static final int COLUMNS = 4;
	private static final int ROWS = 3;
	private static final int TILE = 38;
	private static final int PAGE_SIZE = COLUMNS * ROWS;

	/** Vanilla menu palette, matching the museum side panel. */
	private static final int BODY = 0xFFC6C6C6;
	private static final int LIGHT = 0xFFFFFFFF;
	private static final int DARK = 0xFF555555;
	private static final int BORDER = 0xFF000000;
	private static final int SLOT = 0xFF8B8B8B;
	private static final int GREEN = 0xFF54FC54;

	private void shadowCentered(GuiGraphicsExtractor extractor,
			String text, int centerX, int y, int colour) {
		extractor.text(this.font, text, centerX - this.font.width(text) / 2,
				y, colour, true);
	}

	/** Explicitly shadow-free: centeredText always shadows, which turns dark
	 * text on the grey body into double-printed mush. */
	private void plainCentered(GuiGraphicsExtractor extractor,
			String text, int centerX, int y, int colour) {
		extractor.text(this.font, text, centerX - this.font.width(text) / 2,
				y, colour, false);
	}

	private enum Sort {
		PER_XP_LOW("Coins/XP Low"), PER_XP_HIGH("Coins/XP High"),
		COST("Cost"), XP("XP");

		final String label;

		Sort(String label) {
			this.label = label;
		}
	}

	private EditBox search;
	private Button sortButton;
	private Button wingButton;
	private Button prevButton;
	private Button nextButton;

	private Sort sort = Sort.PER_XP_LOW;
	private int wingIndex = -1;
	private int page;
	private List<MuseumTracker.BrowserEntry> filtered = List.of();

	public MuseumBrowserScreen() {
		super(Component.literal("Museum Browser"));
	}

	private int panelLeft() {
		// Docked to the right of the screen, per the user's layout.
		return this.width - (COLUMNS * TILE + 16) - 24;
	}

	private int panelTop() {
		return this.height / 2 - (ROWS * TILE + 70) / 2;
	}

	@Override
	protected void init() {
		int left = panelLeft();
		int top = panelTop();
		int innerWidth = COLUMNS * TILE + 16;

		search = new EditBox(this.font, left + 8, top + 8,
				innerWidth - 16 - 48, 16, Component.literal("Search"));
		search.setHint(Component.literal("Search..."));
		search.setResponder(text -> {
			page = 0;
			refilter();
		});
		addRenderableWidget(search);

		sortButton = Button.builder(Component.literal("Sort: " + sort.label), button -> {
					sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
					button.setMessage(Component.literal("Sort: " + sort.label));
					refilter();
				})
				.bounds(left + innerWidth - 8 - 46, top + 6, 46, 20)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(
						Component.literal("Sort order")))
				.build();
		addRenderableWidget(sortButton);

		wingButton = Button.builder(Component.literal("Wing: All"), button -> {
					List<String> wings = MuseumTracker.wingNames();
					wingIndex = wingIndex + 1 >= wings.size() ? -1 : wingIndex + 1;
					button.setMessage(Component.literal("Wing: " + (wingIndex < 0 ? "All"
							: prettyWing(wings.get(wingIndex)))));
					page = 0;
					refilter();
				})
				.bounds(left + 8, top + 30, 70, 16)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(
						Component.literal("Filter by wing")))
				.build();
		addRenderableWidget(wingButton);

		int arrowY = top + 52 + ROWS * TILE + 2;
		prevButton = Button.builder(Component.literal("<"), button -> {
					page = Math.max(0, page - 1);
				})
				.bounds(left + 8, arrowY, 20, 16)
				.build();
		addRenderableWidget(prevButton);

		nextButton = Button.builder(Component.literal(">"), button -> {
					page = Math.min(maxPage(), page + 1);
				})
				.bounds(left + innerWidth - 8 - 20, arrowY, 20, 16)
				.build();
		addRenderableWidget(nextButton);

		refilter();
	}

	private void refilter() {
		List<MuseumTracker.BrowserEntry> all = MuseumTracker.browserEntries();
		List<String> wings = MuseumTracker.wingNames();
		String wing = wingIndex < 0 || wingIndex >= wings.size()
				? null : wings.get(wingIndex);
		String query = search == null ? "" : search.getValue()
				.trim().toLowerCase(Locale.ROOT);

		List<MuseumTracker.BrowserEntry> result = new ArrayList<>();

		for (MuseumTracker.BrowserEntry entry : all) {
			if (wing != null && !entry.wing().equals(wing)) {
				continue;
			}

			if (!query.isEmpty()
					&& !entry.name().toLowerCase(Locale.ROOT).contains(query)) {
				continue;
			}

			result.add(entry);
		}

		result.sort((a, b) -> switch (sort) {
			// Unpriced entries sink to the back for the money sorts.
			case PER_XP_LOW -> Double.compare(perXpOrMax(a), perXpOrMax(b));
			case PER_XP_HIGH -> Double.compare(perXpOrMin(b), perXpOrMin(a));
			case COST -> Long.compare(costOrMax(a), costOrMax(b));
			case XP -> Long.compare(b.xp(), a.xp());
		});

		filtered = result;
		page = Math.min(page, maxPage());
	}

	private static double perXpOrMax(MuseumTracker.BrowserEntry entry) {
		return entry.cost() < 0 || entry.xp() <= 0
				? Double.MAX_VALUE : (double) entry.cost() / entry.xp();
	}

	/** For the high-first direction, unpriced maps low so it still sinks. */
	private static double perXpOrMin(MuseumTracker.BrowserEntry entry) {
		return entry.cost() < 0 || entry.xp() <= 0
				? -1 : (double) entry.cost() / entry.xp();
	}

	private static long costOrMax(MuseumTracker.BrowserEntry entry) {
		return entry.cost() < 0 ? Long.MAX_VALUE : entry.cost();
	}

	private int maxPage() {
		return Math.max(0, (filtered.size() - 1) / PAGE_SIZE);
	}

	private int refreshTicks;

	@Override
	public void tick() {
		super.tick();

		// Prices warm in the background; fold them in every couple seconds.
		if (++refreshTicks >= 40) {
			refreshTicks = 0;
			refilter();
		}
	}

	@Override
	public void extractRenderState(
			GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		int left = panelLeft();
		int top = panelTop();
		int innerWidth = COLUMNS * TILE + 16;
		int innerHeight = ROWS * TILE + 70 + 6;

		// The classic bevelled window behind everything.
		extractor.fill(left - 3, top - 3, left + innerWidth + 3, top + innerHeight + 3, BORDER);
		extractor.fill(left - 2, top - 2, left + innerWidth + 2, top + innerHeight + 2, LIGHT);
		extractor.fill(left, top, left + innerWidth + 2, top + innerHeight + 2, DARK);
		extractor.fill(left - 1, top - 1, left + innerWidth + 1, top + innerHeight + 1, BODY);

		super.extractRenderState(extractor, mouseX, mouseY, partialTick);

		lastHovered = null;
		MuseumTracker.BrowserEntry hovered = null;
		int gridTop = top + 52;

		for (int i = 0; i < PAGE_SIZE; i++) {
			int index = page * PAGE_SIZE + i;
			int tileX = left + 8 + (i % COLUMNS) * TILE;
			int tileY = gridTop + (i / COLUMNS) * TILE;

			// A vanilla-looking slot: dark top/left, light bottom/right.
			extractor.fill(tileX, tileY, tileX + TILE - 4, tileY + TILE - 4, 0xFF373737);
			extractor.fill(tileX + 1, tileY + 1, tileX + TILE - 4, tileY + TILE - 4, LIGHT);
			extractor.fill(tileX + 1, tileY + 1, tileX + TILE - 5, tileY + TILE - 5, SLOT);

			if (index >= filtered.size()) {
				continue;
			}

			MuseumTracker.BrowserEntry entry = filtered.get(index);

			if (entry.cost() < 0) {
				MuseumTracker.warmEntry(entry.id());
			}

			extractor.item(MuseumIcons.iconFor(entry.id(), entry.name()),
					tileX + 9, tileY + 4);
			shadowCentered(extractor, headline(entry),
					tileX + (TILE - 4) / 2, tileY + 23, GREEN);

			if (mouseX >= tileX && mouseX < tileX + TILE - 4
					&& mouseY >= tileY && mouseY < tileY + TILE - 4) {
				hovered = entry;
				// The vanilla slot-hover highlight: translucent white square.
				extractor.fill(tileX + 1, tileY + 1, tileX + TILE - 5,
						tileY + TILE - 5, 0x80FFFFFF);
			}
		}

		// Dark, unshadowed body text per the vanilla panel convention.
		plainCentered(extractor, "Page " + (page + 1) + "/" + (maxPage() + 1),
				left + innerWidth / 2, top + 56 + ROWS * TILE, 0xFF404040);

		plainCentered(extractor, "Green: " + switch (sort) {
					case PER_XP_LOW, PER_XP_HIGH -> "coins per XP";
					case COST -> "total cost";
					case XP -> "museum XP";
				},
				left + innerWidth / 2, top + 70 + ROWS * TILE, 0xFF404040);

		if (hovered != null) {
			lastHovered = hovered;
			drawInfo(extractor, mouseX, mouseY, hovered);
		}
	}

	private MuseumTracker.BrowserEntry lastHovered;

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
		// F1/F2 on a hovered tile: prefill the AH or bazaar search for it.
		if (lastHovered != null && (key.key() == 290 || key.key() == 291)) {
			QuickSearchKeys.openSearch(key.key() == 290, lastHovered.name());
			return true;
		}

		return super.keyPressed(key);
	}

	/** The tile's green number, matching the current sort. */
	private String headline(MuseumTracker.BrowserEntry entry) {
		return switch (sort) {
			case PER_XP_LOW, PER_XP_HIGH -> entry.cost() < 0 || entry.xp() <= 0 ? "?"
					: Numbers.shorten(Math.round((double) entry.cost() / entry.xp()));
			case COST -> entry.cost() < 0 ? "?" : Numbers.shorten(entry.cost());
			case XP -> Long.toString(entry.xp());
		};
	}

	/** The hover card: everything known about the entry, vanilla-styled. */
	private void drawInfo(GuiGraphicsExtractor extractor,
			int mouseX, int mouseY, MuseumTracker.BrowserEntry entry) {
		List<String> lines = new ArrayList<>();
		lines.add(entry.name());
		lines.add("Wing: " + prettyWing(entry.wing()));

		if (entry.cost() >= 0) {
			lines.add("Cost: " + Numbers.group(entry.cost()) + " coins");
		} else {
			lines.add("Cost: no live price yet");
		}

		if (entry.xp() > 0) {
			lines.add("Reward: +" + entry.xp() + " XP");

			if (entry.cost() >= 0) {
				lines.add(Numbers.shorten(Math.round(
						(double) entry.cost() / entry.xp())) + " coins per XP");
			}
		}

		lines.add("");
		lines.add("F1: search AH   F2/F4: bazaar");

		MuseumTracker.drawVanillaTooltip(extractor, this.font, mouseX, mouseY, lines);
	}

	private static String prettyWing(String wing) {
		return wing.substring(0, 1).toUpperCase(Locale.ROOT) + wing.substring(1);
	}

	/** A vanilla stand-in icon guessed from the entry's name. */
	static ItemStack iconFor(String name) {
		String upper = name.toUpperCase(Locale.ROOT);

		if (upper.contains("PICKAXE") || upper.contains("DRILL")) {
			return new ItemStack(Items.IRON_PICKAXE);
		}

		if (upper.contains("SWORD") || upper.contains("BLADE")
				|| upper.contains("DAGGER") || upper.contains("KATANA")) {
			return new ItemStack(Items.IRON_SWORD);
		}

		if (upper.contains("BOW")) {
			return new ItemStack(Items.BOW);
		}

		if (upper.contains("AXE")) {
			return new ItemStack(Items.IRON_AXE);
		}

		if (upper.contains("HOE")) {
			return new ItemStack(Items.IRON_HOE);
		}

		if (upper.contains("SHOVEL") || upper.contains("SPADE")) {
			return new ItemStack(Items.IRON_SHOVEL);
		}

		if (upper.contains("ROD")) {
			return new ItemStack(Items.FISHING_ROD);
		}

		if (upper.contains("WAND") || upper.contains("STAFF")
				|| upper.contains("SCEPTRE") || upper.contains("SCEPTER")) {
			return new ItemStack(Items.BLAZE_ROD);
		}

		if (upper.contains("HELMET") || upper.contains("HAT") || upper.contains("MASK")
				|| upper.contains("HOOD") || upper.contains("CAP")) {
			return new ItemStack(Items.IRON_HELMET);
		}

		if (upper.contains("CHESTPLATE") || upper.contains("SHIRT")
				|| upper.contains("TUNIC") || upper.contains("JACKET")) {
			return new ItemStack(Items.IRON_CHESTPLATE);
		}

		if (upper.contains("LEGGINGS") || upper.contains("TROUSERS")) {
			return new ItemStack(Items.IRON_LEGGINGS);
		}

		if (upper.contains("BOOTS") || upper.contains("SLIPPERS")
				|| upper.contains("SNEAKERS")) {
			return new ItemStack(Items.IRON_BOOTS);
		}

		if (upper.contains("ARMOR") || upper.contains("SUIT")) {
			return new ItemStack(Items.DIAMOND_CHESTPLATE);
		}

		if (upper.contains("POTION")) {
			return new ItemStack(Items.POTION);
		}

		if (upper.contains("NECKLACE") || upper.contains("RING")
				|| upper.contains("ARTIFACT") || upper.contains("TALISMAN")
				|| upper.contains("CLOAK") || upper.contains("BELT")
				|| upper.contains("GLOVES") || upper.contains("BRACELET")) {
			return new ItemStack(Items.EMERALD);
		}

		return new ItemStack(Items.BOOK);
	}
}
