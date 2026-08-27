package dev.skyaid.config;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * How far back Coins/h averages: minute steps up to 5, then five-minute steps
 * to an hour, then the whole session at the far right.
 *
 * <p>The positions are a fixed list rather than a snapped continuous range so
 * every step gets the same amount of slider travel - on a continuous scale the
 * four single-minute steps would share a few pixels at the far left.
 *
 * <p>The whole-session position exists because it is a different kind of answer,
 * not just a longer window - "what has this session made" rather than "what is
 * this spot making". In the config it is stored as 0 minutes.
 */
public class CoinsRateWindowSlider extends AbstractSliderButton {
	/** Every position, in minutes; 0 is the whole-session sentinel. */
	static final int[] STEPS =
			{1, 2, 3, 4, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 0};

	public CoinsRateWindowSlider(int x, int y, int width, int height) {
		super(x, y, width, height, Component.empty(),
				toSlider(ConfigManager.get().skyblockHud.coinsPerHourWindowMinutes));
		updateMessage();
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal("Coins/h: " + describe(fromSlider(this.value))));
	}

	@Override
	protected void applyValue() {
		ConfigManager.get().skyblockHud.coinsPerHourWindowMinutes = fromSlider(this.value);
	}

	static String describe(int minutes) {
		if (minutes == 0) {
			return "session";
		}

		return minutes == 60 ? "last 1h" : "last " + minutes + "m";
	}

	private static double toSlider(int configMinutes) {
		return (double) nearestIndex(configMinutes) / (STEPS.length - 1);
	}

	private static int fromSlider(double slider) {
		int index = (int) Math.round(slider * (STEPS.length - 1));

		return STEPS[Math.max(0, Math.min(STEPS.length - 1, index))];
	}

	/** A hand-edited config value lands on the closest position rather than 1m. */
	private static int nearestIndex(int configMinutes) {
		if (configMinutes <= 0) {
			return STEPS.length - 1;
		}

		int best = 0;

		for (int i = 0; i < STEPS.length - 1; i++) {
			if (Math.abs(STEPS[i] - configMinutes) < Math.abs(STEPS[best] - configMinutes)) {
				best = i;
			}
		}

		return best;
	}
}
