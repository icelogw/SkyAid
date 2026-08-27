package dev.skyaid.hud;

import dev.skyaid.config.Config;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.parse.HudLayout;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reorders the HUD's lines and places dividers between them.
 *
 * <p>The list here is the saved order in full, including elements currently
 * switched off or with nothing to show. Hiding those would make the list jump
 * around as you played and leave no way to position a readout before it appears.
 * What actually gets drawn is decided at render time by {@link HudLayout}.
 */
public class HudArrangeScreen extends Screen {
	private static final int ROW_HEIGHT = 22;
	/**
	 * Wide enough for the divider label without clipping. It was narrowed to make
	 * room for the On/Off button, which left the longest entry cut off.
	 */
	private static final int LABEL_WIDTH = 180;
	private static final int TOGGLE_WIDTH = 38;
	private static final int SMALL = 20;
	private static final int GAP = 2;

	/** Rows start below the title, the hint line, and the preset switch. */
	private static final int TOP = 62;
	private static final int PRESET_Y = 38;
	private static final int ROWS_WIDTH =
			LABEL_WIDTH + GAP + TOGGLE_WIDTH + (SMALL + GAP) * 3;

	private final Screen parent;

	private List<String> order;

	/**
	 * Which arrangement is being edited: -1 for the standard layout, otherwise
	 * an index into the zone profiles. Starts on whichever is active right now,
	 * so opening this screen inside a dungeon edits what is on screen.
	 */
	int editing = -1;

	/** init() reruns on every resize; only the first run may auto-pick. */
	private boolean autoPicked;

	/**
	 * Index of the first visible row. The list can be taller than a small window,
	 * so it scrolls by whole rows - the wheel moves it, and a bar beside the list
	 * shows where you are. Widgets outside the view are simply not built.
	 */
	private int scrollRow;

	public HudArrangeScreen(Screen parent) {
		super(Component.literal("Arrange the HUD"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Config.HudSettings hud = ConfigManager.get().skyblockHud;

		if (!autoPicked) {
			autoPicked = true;
			editing = hud.profiles.indexOf(hud.activeProfile(
					dev.skyaid.core.SkyblockTracker.state().location().orElse(null)));
		}

		editing = Math.min(editing, hud.profiles.size() - 1);
		loadOrder();
		rebuildRows();
	}

	void loadOrder() {
		Config.HudSettings hud = ConfigManager.get().skyblockHud;
		order = new ArrayList<>(HudLayout.sanitise(
				editing >= 0 ? hud.profiles.get(editing).layout : hud.layout));
	}

	/** The switch set of whichever arrangement is being edited. */
	private Config.HudElements elements() {
		Config.HudSettings hud = ConfigManager.get().skyblockHud;
		return editing >= 0 ? hud.profiles.get(editing).elements : hud.elements;
	}

	private String editingName() {
		Config.HudSettings hud = ConfigManager.get().skyblockHud;
		return editing >= 0 ? hud.profiles.get(editing).zone : "Standard layout";
	}

	/** How many rows fit between the header and the pinned footer. */
	private int capacity() {
		return Math.max(1, (this.height - 28 - 8 - TOP) / ROW_HEIGHT);
	}

	/**
	 * Entries that appear as rows. Removal markers sit at the end of the order
	 * (sanitise puts them there) and are bookkeeping, not rows.
	 */
	private int visibleCount() {
		int count = 0;

		while (count < order.size() && !HudLayout.isRemoval(order.get(count))) {
			count++;
		}

		return count;
	}

	/** Whether this arrangement currently shows the element as a row. */
	boolean hasElement(String id) {
		return order.contains(id);
	}

	/** Appends a divider at the end of the visible rows. */
	void addDivider() {
		order.add(visibleCount(), HudLayout.DIVIDER);
		scrollRow = order.size();
		save();
		rebuildRows();
	}

	/** Brings a removed element back, at the end of the visible rows. */
	void restore(String id) {
		if (hasElement(id)) {
			return;
		}

		order.remove(HudLayout.removalMarker(id));
		order.add(visibleCount(), id);
		scrollRow = order.size();
		save();
		rebuildRows();
	}

	private void rebuildRows() {
		clearWidgets();

		int left = this.width / 2 - ROWS_WIDTH / 2;
		int capacity = capacity();

		// The profile row: cycle through Standard plus every zone profile, add a
		// new profile, or remove the one being edited.
		Config.HudSettings hud = ConfigManager.get().skyblockHud;
		int cycleWidth = ROWS_WIDTH - (SMALL + GAP) * 2;

		addRenderableWidget(Button.builder(
						Component.literal("Editing: " + editingName()),
						button -> {
							editing = editing + 1 >= hud.profiles.size() ? -1 : editing + 1;
							scrollRow = 0;
							loadOrder();
							rebuildRows();
						})
				.bounds(left, PRESET_Y, cycleWidth, SMALL)
				.build());

		addRenderableWidget(Button.builder(Component.literal("+"),
						button -> this.minecraft.setScreenAndShow(new HudProfileScreen(this)))
				.bounds(left + cycleWidth + GAP, PRESET_Y, SMALL, SMALL)
				.build());

		// The standard layout is the fallback for everywhere, and the Catacombs
		// profile ships with the mod - neither can go. User profiles can.
		Button remove = Button.builder(Component.literal("x"), button -> {
			hud.profiles.remove(editing);
			editing = -1;
			scrollRow = 0;
			ConfigManager.save();
			loadOrder();
			rebuildRows();
		}).bounds(left + cycleWidth + GAP + SMALL + GAP, PRESET_Y, SMALL, SMALL).build();
		remove.active = editing >= 0 && !hud.profiles.get(editing).builtin;
		addRenderableWidget(remove);

		int visible = visibleCount();
		scrollRow = Math.max(0, Math.min(scrollRow, visible - capacity));

		int shown = Math.min(capacity, visible - scrollRow);

		for (int i = 0; i < shown; i++) {
			addRow(scrollRow + i, left, TOP + i * ROW_HEIGHT);
		}

		// One row of three, sat just under the list: the list is tall enough now
		// that stacking these full-width pushed them off short windows.
		int footer = Math.min(TOP + shown * ROW_HEIGHT + 8, this.height - 28);
		int third = (ROWS_WIDTH - GAP * 2) / 3;

		addRenderableWidget(Button.builder(Component.literal("Add..."),
						button -> this.minecraft.setScreenAndShow(new HudAddScreen(this)))
				.bounds(left, footer, third, SMALL)
				.build());

		// Restores the shipped layout in full - order, dividers and the On/Off
		// switches - for whichever arrangement is being edited. The Catacombs
		// profile has its own shipped shape.
		addRenderableWidget(Button.builder(Component.literal("Reset"),
						button -> {
							String zone = editing >= 0
									? hud.profiles.get(editing).zone : "";
							order = new ArrayList<>(switch (zone) {
								case "The Catacombs" -> HudLayout.catacombsOrder();
								case "The Garden" -> HudLayout.gardenOrder();
								default -> HudLayout.defaultOrder();
							});

							if (editing >= 0) {
								hud.profiles.get(editing).elements = new Config.HudElements();
							} else {
								hud.elements = new Config.HudElements();
							}

							scrollRow = 0;
							save();
							rebuildRows();
						})
				.bounds(left + third + GAP, footer, third, SMALL)
				.build());

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.bounds(left + (third + GAP) * 2, footer, ROWS_WIDTH - (third + GAP) * 2, SMALL)
				.build());
	}

	private void addRow(int index, int left, int y) {
		String id = order.get(index);
		boolean divider = HudLayout.DIVIDER.equals(id);
		Config.HudElements elements = elements();
		boolean shown = divider || elements.isShown(id);

		// The name is a label, not a control. Dimmed when the readout is switched
		// off, so the list shows at a glance what will actually be drawn.
		Button label = Button.builder(Component.literal(displayName(id))
				.withStyle(shown ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY), button -> {
		}).bounds(left, y, LABEL_WIDTH, SMALL).build();
		label.active = false;
		addRenderableWidget(label);

		int x = left + LABEL_WIDTH + GAP;

		Button toggle = Button.builder(
						Component.literal(shown ? "On" : "Off")
								.withStyle(shown ? ChatFormatting.GREEN : ChatFormatting.RED),
						button -> {
							elements.setShown(id, !elements.isShown(id));
							ConfigManager.save();
							rebuildRows();
						})
				.bounds(x, y, TOGGLE_WIDTH, SMALL)
				.build();
		// A divider has nothing to switch off; it appears wherever it still separates
		// two visible lines, and is removed with the x instead.
		toggle.active = !divider;
		addRenderableWidget(toggle);

		x += TOGGLE_WIDTH + GAP;

		Button up = Button.builder(Component.literal("^"), button -> move(index, -1))
				.bounds(x, y, SMALL, SMALL)
				.build();
		up.active = index > 0;
		addRenderableWidget(up);

		Button down = Button.builder(Component.literal("v"), button -> move(index, 1))
				.bounds(x + SMALL + GAP, y, SMALL, SMALL)
				.build();
		down.active = index < visibleCount() - 1;
		addRenderableWidget(down);

		// Removing an element takes it out of this arrangement entirely; a marker
		// remembers the choice so it stays gone, and Add... offers it back.
		Button remove = Button.builder(Component.literal("x"), button -> {
			order.remove(index);

			if (!divider) {
				order.add(HudLayout.removalMarker(id));
			}

			save();
			rebuildRows();
		}).bounds(x + (SMALL + GAP) * 2, y, SMALL, SMALL).build();
		addRenderableWidget(remove);
	}
	private void move(int index, int by) {
		int target = index + by;

		if (target < 0 || target >= visibleCount()) {
			return;
		}

		String moved = order.remove(index);
		order.add(target, moved);

		// Follow the row being moved, so repeated clicks keep working at the
		// edges of the view instead of pushing it out of sight.
		if (target < scrollRow) {
			scrollRow = target;
		} else if (target >= scrollRow + capacity()) {
			scrollRow = target - capacity() + 1;
		}

		save();
		rebuildRows();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
			return true;
		}

		if (visibleCount() <= capacity() || scrollY == 0) {
			return false;
		}

		scrollRow -= (int) Math.signum(scrollY);
		rebuildRows();
		return true;
	}

	private void save() {
		Config.HudSettings hud = ConfigManager.get().skyblockHud;

		if (editing >= 0) {
			hud.profiles.get(editing).layout = new ArrayList<>(order);
		} else {
			hud.layout = new ArrayList<>(order);
		}

		ConfigManager.save();
	}

	static String displayName(String id) {
		if (HudLayout.DIVIDER.equals(id)) {
			return "-------- divider --------";
		}

		return switch (id) {
			case "other" -> "Other lines";
			case "slayer" -> "Slayer";
			case "party" -> "Party info";
			case "dungeon" -> "Dungeon info";
			case "commissions" -> "Commissions";
			case "garden" -> "Garden info";
			case "bank" -> "Bank";
			case "skill" -> "Skill progress";
			case "composter" -> "Composter";
			case "visitors" -> "Visitors";
			case "gardenlevel" -> "Garden level";
			case "fortune" -> "Fortune stats";
			case "pests" -> "Pests";
			case "milestone" -> "Crop milestone";
			case "croprate" -> "Crops/min";
			case "align" -> "Row align";
			case "skillrate" -> "Skill XP/hr";
			case "drops" -> "Drop tracker";
			case "museum" -> "Museum progress";
			case "jacob" -> "Jacob's contest";
			case "cooldown" -> "Ability cooldown";
			case "powder" -> "Powder & HOTM";
			case "nucleus" -> "Nucleus runs";
			case "gemstones" -> "Gemstone mining";
			case "pet" -> "Pet";
			case "health" -> "HP";
			case "coinsgained" -> "Coins gained";
			case "coinshour" -> "Coins/h";
			case "bitsgained" -> "Bits gained";
			default -> id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
		};
	}

	@Override
	public void extractRenderState(
			GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(extractor, mouseX, mouseY, partialTick);

		extractor.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
		extractor.centeredText(this.font,
				Component.literal("Dividers only show between two visible lines")
						.withStyle(ChatFormatting.DARK_GRAY),
				this.width / 2, 27, 0xFF888888);

		drawScrollbar(extractor);
	}

	/** Track and thumb beside the list, only when there is something to scroll to. */
	private void drawScrollbar(GuiGraphicsExtractor extractor) {
		int capacity = capacity();
		int visible = visibleCount();

		if (visible <= capacity) {
			return;
		}

		int x = this.width / 2 + ROWS_WIDTH / 2 + 4;
		int trackHeight = capacity * ROW_HEIGHT - GAP;

		int thumbHeight = Math.max(8, trackHeight * capacity / visible);
		int travel = trackHeight - thumbHeight;
		int thumbY = TOP + travel * scrollRow / (visible - capacity);

		extractor.fill(x, TOP, x + 4, TOP + trackHeight, 0xFF2B2B2B);
		extractor.fill(x, thumbY, x + 4, thumbY + thumbHeight, 0xFFAAAAAA);
	}

	@Override
	public void onClose() {
		save();
		this.minecraft.setScreenAndShow(parent);
	}
}
