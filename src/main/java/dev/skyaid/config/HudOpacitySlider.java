package dev.skyaid.config;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** The HUD panel's darkness, 0-100% in steps of ten. */
public class HudOpacitySlider extends AbstractSliderButton {
	private static final int STEP = 10;

	public HudOpacitySlider(int x, int y, int width, int height) {
		super(x, y, width, height, Component.empty(),
				Math.max(0, Math.min(100, ConfigManager.get().skyblockHud.backgroundOpacity))
						/ 100.0);
		updateMessage();
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal("Panel opacity: " + snapped(this.value) + "%"));
	}

	@Override
	protected void applyValue() {
		ConfigManager.get().skyblockHud.backgroundOpacity = snapped(this.value);
	}

	private static int snapped(double slider) {
		int raw = (int) Math.round(slider * 100);
		return Math.max(0, Math.min(100, Math.round((float) raw / STEP) * STEP));
	}
}
