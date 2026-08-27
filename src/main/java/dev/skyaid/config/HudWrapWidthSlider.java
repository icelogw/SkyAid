package dev.skyaid.config;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * Where the HUD wraps long free-text lines (objectives, commissions):
 * 100-300 pixels in steps of 25. The default 150 is the width the user
 * settled on by screenshot.
 */
public class HudWrapWidthSlider extends AbstractSliderButton {
	private static final int MIN = 100;
	private static final int MAX = 300;
	private static final int STEP = 25;

	public HudWrapWidthSlider(int x, int y, int width, int height) {
		super(x, y, width, height, Component.empty(),
				(double) (clamp(ConfigManager.get().skyblockHud.wrapWidth) - MIN)
						/ (MAX - MIN));
		updateMessage();
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal("Wrap width: " + fromSlider(this.value) + "px"));
	}

	@Override
	protected void applyValue() {
		ConfigManager.get().skyblockHud.wrapWidth = fromSlider(this.value);
	}

	private static int fromSlider(double slider) {
		int raw = (int) Math.round(MIN + (MAX - MIN) * slider);
		return clamp(Math.round((float) raw / STEP) * STEP);
	}

	private static int clamp(int value) {
		return Math.max(MIN, Math.min(MAX, value));
	}
}
