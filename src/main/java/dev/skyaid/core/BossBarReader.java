package dev.skyaid.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The text of every boss bar currently on screen.
 *
 * <p>Hypixel uses boss bars for more than bosses: the zone-quest objective is
 * one ("Objective: Talk to Fisherwoman Enid."), which is why the sidebar dumps
 * never contained it. Reaches the private bar map through the access widener;
 * making sense of the text is {@link dev.skyaid.parse.BossBars}' job, so this
 * stays as dumb as {@link ScoreboardReader}.
 */
public final class BossBarReader {
	private BossBarReader() {
	}

	public static List<String> barNames() {
		Minecraft client = Minecraft.getInstance();

		if (client.gui == null) {
			return List.of();
		}

		List<String> names = new ArrayList<>();

		for (LerpingBossEvent event : client.gui.hud.getBossOverlay().events.values()) {
			names.add(event.getName().getString());
		}

		return names;
	}
}
