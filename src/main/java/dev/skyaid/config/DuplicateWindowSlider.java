package dev.skyaid.config;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * How far back the repeated-message filter looks, from 5 seconds to 5 minutes.
 *
 * <p>Snaps to 5-second steps. The slider is a few hundred pixels covering a
 * five-minute range, so without snapping a single pixel of travel would be
 * several seconds and the value would be impossible to set deliberately.
 */
public class DuplicateWindowSlider extends AbstractSliderButton {
	static final int MIN_SECONDS = 5;
	static final int MAX_SECONDS = 300;

	private static final int STEP_SECONDS = 5;

	public DuplicateWindowSlider(int x, int y, int width, int height) {
		super(x, y, width, height, Component.empty(),
				toSlider(ConfigManager.get().chat.duplicateWindowSeconds));
		updateMessage();
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal("Repeat window: " + describe(fromSlider(this.value))));
	}

	@Override
	protected void applyValue() {
		ConfigManager.get().chat.duplicateWindowSeconds = fromSlider(this.value);
	}

	/** Reads as "45s", "1m" or "2m 30s" rather than a bare count of seconds. */
	static String describe(int seconds) {
		if (seconds < 60) {
			return seconds + "s";
		}

		int minutes = seconds / 60;
		int remainder = seconds % 60;

		return remainder == 0 ? minutes + "m" : minutes + "m " + remainder + "s";
	}

	private static double toSlider(int seconds) {
		int clamped = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
		return (double) (clamped - MIN_SECONDS) / (MAX_SECONDS - MIN_SECONDS);
	}

	private static int fromSlider(double slider) {
		int raw = (int) Math.round(MIN_SECONDS + (MAX_SECONDS - MIN_SECONDS) * slider);
		int snapped = Math.round((float) raw / STEP_SECONDS) * STEP_SECONDS;

		return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, snapped));
	}
}
