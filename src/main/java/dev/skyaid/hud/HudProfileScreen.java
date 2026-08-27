package dev.skyaid.hud;

import dev.skyaid.config.Config;
import dev.skyaid.config.ConfigManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Creates a new HUD profile: type the zone, get a fresh arrangement for it.
 *
 * <p>The zone text is both the profile's name and its trigger - it applies
 * wherever the sidebar location starts with it, so "The Catacombs" covers every
 * floor and "The End" covers both End zones. The new profile starts as the
 * shipped default layout, ready to be rearranged.
 */
public class HudProfileScreen extends Screen {
	private static final int WIDTH = 220;
	private static final int HEIGHT = 20;

	private final HudArrangeScreen parent;

	private EditBox zoneBox;
	private Button add;

	public HudProfileScreen(HudArrangeScreen parent) {
		super(Component.literal("New HUD profile"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDTH / 2;
		int y = this.height / 2 - 20;

		zoneBox = new EditBox(this.font, x, y, WIDTH, HEIGHT, Component.literal("Zone"));
		zoneBox.setMaxLength(48);
		zoneBox.setHint(Component.literal("zone name, e.g. The Park"));
		zoneBox.setResponder(text -> add.active = !text.isBlank());
		addRenderableWidget(zoneBox);

		add = Button.builder(Component.literal("Add"), button -> create())
				.bounds(x, y + 28, WIDTH / 2 - 2, HEIGHT)
				.build();
		add.active = false;
		addRenderableWidget(add);

		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
				.bounds(x + WIDTH / 2 + 2, y + 28, WIDTH / 2 - 2, HEIGHT)
				.build());

		setInitialFocus(zoneBox);
	}

	private void create() {
		String zone = zoneBox.getValue().trim();

		if (zone.isEmpty()) {
			return;
		}

		Config.HudProfile profile = new Config.HudProfile();
		profile.zone = zone;

		var profiles = ConfigManager.get().skyblockHud.profiles;
		profiles.add(profile);
		ConfigManager.save();

		// Hand the arrange screen straight to the new profile.
		parent.editing = profiles.size() - 1;
		onClose();
	}

	@Override
	public void extractRenderState(
			GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(extractor, mouseX, mouseY, partialTick);

		extractor.centeredText(this.font, this.title,
				this.width / 2, this.height / 2 - 48, 0xFFFFFFFF);
		extractor.centeredText(this.font,
				Component.literal("Applies where the location starts with this")
						.withStyle(ChatFormatting.GRAY),
				this.width / 2, this.height / 2 - 34, 0xFF999999);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreenAndShow(parent);
	}
}
