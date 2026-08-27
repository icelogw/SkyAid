package dev.skyaid.hud;

import net.minecraft.network.chat.Component;

/**
 * One row of the HUD: either a line of text, or a separator rule.
 *
 * <p>A divider is not text made of dashes - it is drawn as a rule spanning the
 * readout's width, so it lines up whatever the lines around it happen to say.
 */
public record HudLine(Component text, boolean divider) {
	public static HudLine of(Component text) {
		return new HudLine(text, false);
	}

	/**
	 * Named separator rather than divider: a record generates an accessor for its
	 * divider component, and a static factory of the same name collides with it.
	 */
	public static HudLine separator() {
		return new HudLine(Component.empty(), true);
	}
}
