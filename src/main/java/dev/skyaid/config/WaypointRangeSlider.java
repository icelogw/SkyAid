package dev.skyaid.config;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * How far away dungeon markers - secret boxes and waypoint beacons alike -
 * stay visible: 10 to 250 blocks in steps of ten so the value can be set
 * deliberately rather than pixel-hunted, plus one stop past the end that
 * reads "No limit" (stored as {@link Config#WAYPOINT_RANGE_NO_LIMIT}).
 */
public class WaypointRangeSlider extends AbstractSliderButton {
	static final int MIN_BLOCKS = 10;
	static final int MAX_BLOCKS = 250;

	private static final int STEP_BLOCKS = 10;

	/** 10, 20 ... 100, then the no-limit stop: one extra step position. */
	private static final int STEPS =
			(MAX_BLOCKS - MIN_BLOCKS) / STEP_BLOCKS + 1;

	public WaypointRangeSlider(int x, int y, int width, int height) {
		super(x, y, width, height, Component.empty(),
				toSlider(ConfigManager.get().waypointRenderDistance));
		updateMessage();
	}

	@Override
	protected void updateMessage() {
		int blocks = fromSlider(this.value);
		setMessage(Component.literal(blocks == Config.WAYPOINT_RANGE_NO_LIMIT
				? "Marker range: No limit"
				: "Marker range: " + blocks + "m"));
	}

	@Override
	protected void applyValue() {
		ConfigManager.get().waypointRenderDistance = fromSlider(this.value);
	}

	private static double toSlider(int blocks) {
		if (blocks == Config.WAYPOINT_RANGE_NO_LIMIT) {
			return 1.0;
		}

		int clamped = Math.max(MIN_BLOCKS, Math.min(MAX_BLOCKS, blocks));
		return (double) (clamped - MIN_BLOCKS) / (STEP_BLOCKS * STEPS);
	}

	private static int fromSlider(double slider) {
		int step = (int) Math.round(slider * STEPS);

		if (step >= STEPS) {
			return Config.WAYPOINT_RANGE_NO_LIMIT;
		}

		return MIN_BLOCKS + step * STEP_BLOCKS;
	}
}
