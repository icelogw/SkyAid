package dev.skyaid.core;

import dev.skyaid.parse.ActionBarParser;
import dev.skyaid.parse.ActionBarState;
import dev.skyaid.parse.BossBars;
import dev.skyaid.parse.ScoreboardParser;
import dev.skyaid.parse.SkyblockState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.Optional;

/**
 * Keeps the current Skyblock state up to date.
 *
 * <p>The sidebar is re-read on a tick interval rather than every tick: parsing it
 * allocates, the values change at most once a second, and this runs on the client
 * thread. The action bar is event-driven instead, since Hypixel pushes it.
 *
 * <p>Everything here is inert off Hypixel - {@link HypixelDetector} is checked
 * before any work is done.
 */
public final class SkyblockTracker {
	/** 20 ticks per second; 5 gives four sidebar reads a second, which is ample. */
	private static final int TICKS_BETWEEN_READS = 5;

	private static volatile SkyblockState state = SkyblockState.EMPTY;
	private static volatile ActionBarState actionBar = ActionBarState.EMPTY;

	/** Kept verbatim so /skyaid dump can show exactly what Hypixel sent. */
	private static volatile String rawActionBar = "";
	private static int tickCounter;

	private SkyblockTracker() {
	}

	public static SkyblockState state() {
		return state;
	}

	public static String rawActionBar() {
		return rawActionBar;
	}

	public static ActionBarState actionBar() {
		return actionBar;
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!HypixelDetector.isOnHypixel()) {
				// Clear on leaving, so a stale purse cannot linger on another server.
				if (state != SkyblockState.EMPTY) {
					state = SkyblockState.EMPTY;
					actionBar = ActionBarState.EMPTY;
					rawActionBar = "";
				}

				return;
			}

			if (++tickCounter < TICKS_BETWEEN_READS) {
				return;
			}

			tickCounter = 0;
			SkyblockState parsed =
					ScoreboardParser.parse(ScoreboardReader.title(), ScoreboardReader.lines());

			// The zone-quest objective is a boss bar, not a sidebar line; graft it
			// on when present. The sidebar-based matcher still fills the field in
			// any context that does put an Objective block on the sidebar.
			if (parsed.inSkyblock()) {
				Optional<String> objective = BossBars.objective(BossBarReader.barNames());

				if (objective.isPresent()) {
					parsed = parsed.withObjective(objective.get());
				}
			}

			state = parsed;
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			// overlay == true means this is the action bar rather than a chat line.
			if (!overlay || !HypixelDetector.isOnHypixel()) {
				return;
			}

			rawActionBar = message.getString();
			actionBar = ActionBarParser.parse(rawActionBar);
		});
	}
}
