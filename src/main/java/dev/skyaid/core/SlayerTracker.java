package dev.skyaid.core;

import dev.skyaid.parse.SlayerTimer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Feeds the slayer boss stopwatch from the tracked sidebar state, once per
 * tick. Reading only - the timer just watches the quest lines Hypixel already
 * shows and remembers when they changed.
 */
public final class SlayerTracker {
	private static final SlayerTimer TIMER = new SlayerTimer();

	private SlayerTracker() {
	}

	public static SlayerTimer timer() {
		return TIMER;
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.level == null) {
				TIMER.reset();
				return;
			}

			TIMER.observe(SkyblockTracker.state().slayerStatus().orElse(null),
					System.currentTimeMillis());
		});
	}
}
