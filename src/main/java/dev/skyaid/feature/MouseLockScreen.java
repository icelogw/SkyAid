package dev.skyaid.feature;

import dev.skyaid.config.Config;
import dev.skyaid.config.ConfigManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * The mouse-lock preset manager: named GROUPS of angles - one per farm
 * layout, "Sugarcane", "Melon" - each holding the six slots the hold-keys
 * read. Switching the active group re-aims every key at that farm's angles;
 * each slot row saves the current camera view or clears.
 *
 * <p>Opened with {@code /skyaid mouselock menu}. Same classic panel style
 * as the search and earn screens.
 */
public class MouseLockScreen extends Screen {
	private static final int WIDTH = 330;
	private static final int SLOTS = 6;
	private static final int ROW = 24;
	private static final int HEADER = 52;
	private static final int FOOTER = 46;
	private static final int PANEL_HEIGHT = HEADER + SLOTS * ROW + FOOTER;

	/** Vanilla menu palette; dark text without shadow on the light body. */
	private static final int BODY = 0xFFC6C6C6;
	private static final int LIGHT = 0xFFFFFFFF;
	private static final int DARK = 0xFF555555;
	private static final int BORDER = 0xFF000000;
	private static final int TEXT = 0xFF404040;
	private static final int MUTED = 0xFF6E6E6E;
	private static final int VALUE = 0xFF1F6B1F;

	public MouseLockScreen() {
		super(Component.literal("Mouse lock presets"));
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDTH / 2;
		int top = panelTop();
		Config config = ConfigManager.get();
		Config.MouseLockGroup group = MouseLock.group();

		// The group row: cycle through groups, add, rename, delete.
		addRenderableWidget(Button.builder(
						Component.literal("Group: " + group.name),
						button -> {
							config.mouseLockActiveGroup =
									(config.mouseLockActiveGroup + 1)
											% config.mouseLockGroups.size();
							ConfigManager.save();
							rebuildWidgets();
						})
				.bounds(x, top + 22, WIDTH - 110, 20)
				.build());

		addRenderableWidget(Button.builder(Component.literal("+"),
						button -> {
							var fresh = new Config.MouseLockGroup();
							fresh.name = "Group " + (config.mouseLockGroups.size() + 1);
							config.mouseLockGroups.add(fresh);
							config.mouseLockActiveGroup =
									config.mouseLockGroups.size() - 1;
							ConfigManager.save();
							this.minecraft.setScreenAndShow(
									new NameScreen(this, fresh));
						})
				.bounds(x + WIDTH - 106, top + 22, 20, 20)
				.build());

		addRenderableWidget(Button.builder(Component.literal("Rename"),
						button -> this.minecraft.setScreenAndShow(
								new NameScreen(this, group)))
				.bounds(x + WIDTH - 82, top + 22, 58, 20)
				.build());

		Button delete = Button.builder(Component.literal("x"),
						button -> {
							config.mouseLockGroups.remove(group);
							config.mouseLockActiveGroup = 0;
							ConfigManager.save();
							rebuildWidgets();
						})
				.bounds(x + WIDTH - 20, top + 22, 20, 20)
				.build();
		delete.active = config.mouseLockGroups.size() > 1;
		addRenderableWidget(delete);

		int listTop = top + HEADER;

		for (int slot = 1; slot <= SLOTS; slot++) {
			int rowTop = listTop + (slot - 1) * ROW;
			int slotIndex = slot;

			addRenderableWidget(Button.builder(Component.literal("Set"),
							button -> {
								var player = this.minecraft.player;

								if (player == null) {
									return;
								}

								List<Float> angles = group.angles;

								while (angles.size() < slotIndex * 2) {
									angles.add(null);
								}

								angles.set(slotIndex * 2 - 2, player.getYRot());
								angles.set(slotIndex * 2 - 1, player.getXRot());
								ConfigManager.save();
								rebuildWidgets();
							})
					.bounds(x + WIDTH - 92, rowTop, 44, 18)
					.build());

			Button clear = Button.builder(Component.literal("Clear"),
							button -> {
								List<Float> angles = group.angles;

								if (angles.size() >= slotIndex * 2) {
									angles.set(slotIndex * 2 - 2, null);
									angles.set(slotIndex * 2 - 1, null);
									ConfigManager.save();
									rebuildWidgets();
								}
							})
					.bounds(x + WIDTH - 44, rowTop, 44, 18)
					.build();
			clear.active = slotAngle(group, slot) != null;
			addRenderableWidget(clear);
		}

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE,
						button -> this.minecraft.setScreenAndShow(null))
				.bounds(this.width / 2 - 50, top + PANEL_HEIGHT - 24, 100, 20)
				.build());
	}

	/** {yaw, pitch} for a slot, or null while it is empty. */
	private static float[] slotAngle(Config.MouseLockGroup group, int slot) {
		List<Float> angles = group.angles;

		if (angles.size() < slot * 2 || angles.get(slot * 2 - 2) == null
				|| angles.get(slot * 2 - 1) == null) {
			return null;
		}

		return new float[]{angles.get(slot * 2 - 2), angles.get(slot * 2 - 1)};
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

		extractor.fill(left - 1, top - 1, right + 1, bottom + 1, BORDER);
		extractor.fill(left, top, right, bottom, BODY);
		extractor.fill(left, top, right, top + 2, LIGHT);
		extractor.fill(left, top, left + 2, bottom, LIGHT);
		extractor.fill(left, bottom - 2, right, bottom, DARK);
		extractor.fill(right - 2, top, right, bottom, DARK);

		super.extractRenderState(extractor, mouseX, mouseY, partialTick);

		String heading = this.title.getString();
		extractor.text(this.font, heading,
				this.width / 2 - this.font.width(heading) / 2, top + 8, TEXT, false);

		Config.MouseLockGroup group = MouseLock.group();
		int listTop = top + HEADER;

		for (int slot = 1; slot <= SLOTS; slot++) {
			int rowTop = listTop + (slot - 1) * ROW;
			float[] angle = slotAngle(group, slot);

			extractor.text(this.font, "Key " + slot + ":",
					x + 4, rowTop + 5, TEXT, false);

			if (angle == null) {
				extractor.text(this.font, "(empty - look and press Set)",
						x + 48, rowTop + 5, MUTED, false);
			} else {
				extractor.text(this.font, String.format(Locale.ROOT,
								"Yaw %.1f   Pitch %.1f", angle[0], angle[1]),
						x + 48, rowTop + 5, VALUE, false);
			}
		}

		String note = "The hold-keys (Controls > SkyAid) use this group.";
		extractor.text(this.font, note,
				this.width / 2 - this.font.width(note) / 2,
				listTop + SLOTS * ROW + 4, MUTED, false);
	}

	/** A small naming popup: one box, OK, Cancel. */
	private static class NameScreen extends Screen {
		private final MouseLockScreen parent;
		private final Config.MouseLockGroup group;
		private EditBox box;

		NameScreen(MouseLockScreen parent, Config.MouseLockGroup group) {
			super(Component.literal("Name the group"));
			this.parent = parent;
			this.group = group;
		}

		@Override
		protected void init() {
			int x = this.width / 2 - 100;
			int y = this.height / 2 - 30;

			box = new EditBox(this.font, x, y, 200, 20, Component.literal("Name"));
			box.setMaxLength(24);
			box.setValue(group.name);
			addRenderableWidget(box);
			setInitialFocus(box);

			addRenderableWidget(Button.builder(CommonComponents.GUI_DONE,
							button -> save())
					.bounds(x, y + 28, 98, 20)
					.build());
			addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL,
							button -> onClose())
					.bounds(x + 102, y + 28, 98, 20)
					.build());
		}

		@Override
		public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
			if (key.key() == 257 || key.key() == 335) {
				save();
				return true;
			}

			return super.keyPressed(key);
		}

		private void save() {
			String name = box.getValue().trim();

			if (!name.isEmpty()) {
				group.name = name;
				ConfigManager.save();
			}

			onClose();
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor extractor,
				int mouseX, int mouseY, float partialTick) {
			super.extractRenderState(extractor, mouseX, mouseY, partialTick);
			extractor.centeredText(this.font, this.title,
					this.width / 2, this.height / 2 - 46, 0xFFFFFFFF);
		}

		@Override
		public void onClose() {
			this.minecraft.setScreenAndShow(parent);
		}
	}
}
