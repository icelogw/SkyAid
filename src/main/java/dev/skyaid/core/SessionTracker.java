package dev.skyaid.core;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.parse.BankTransfers;
import dev.skyaid.parse.SessionStats;
import dev.skyaid.parse.SkyblockState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Feeds {@link SessionStats} while the player is in Skyblock.
 *
 * <p>A session lasts until the game closes or {@code /skyaid session reset}.
 * Deliberately not cleared on disconnect: Hypixel bounces players through
 * lobbies and the occasional relog, and losing an hour of tracking to a kick
 * would make the numbers useless on exactly the long sessions they are for.
 * Time spent outside Skyblock never counts - {@link SessionStats} caps the gap
 * between observations, and this only observes while in Skyblock.
 *
 * <p>One thing this cannot see: switching Skyblock profiles moves the purse
 * without anything being earned or spent, which lands in the totals as one big
 * jump. The reset command is the answer.
 */
public final class SessionTracker {
	private static SessionStats session = new SessionStats();

	/** Read by the HUD each frame; volatile so it never sees a torn update. */
	private static volatile SessionStats.Snapshot snapshot = SessionStats.Snapshot.EMPTY;

	private SessionTracker() {
	}

	public static SessionStats.Snapshot snapshot() {
		return snapshot;
	}

	public static void reset() {
		session = new SessionStats();
		snapshot = SessionStats.Snapshot.EMPTY;
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!HypixelDetector.isOnHypixel()) {
				return;
			}

			SkyblockState state = SkyblockTracker.state();

			if (!state.inSkyblock()) {
				return;
			}

			session.observe(System.currentTimeMillis(), state.purse(), state.bits());
			snapshot = session.snapshot(
					ConfigManager.get().skyblockHud.coinsPerHourWindowMinutes * 60_000L);
		});

		// Bank deposits and withdrawals move the purse without anything being
		// earned. Hypixel confirms each one in chat, and that confirmation is
		// what lets the session math cancel the transfer back out.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !HypixelDetector.isOnHypixel()) {
				return;
			}

			if (!SkyblockTracker.state().inSkyblock()) {
				return;
			}

			BankTransfers.intoPurse(message.getString())
					.ifPresent(intoPurse -> session.observeBankTransfer(intoPurse));
		});
	}
}
