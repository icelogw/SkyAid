package dev.skyaid.core;

import dev.skyaid.config.ConfigManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayDeque;
import java.util.Optional;

/**
 * Crops-per-minute from the held farming tool's own Cultivating counter -
 * {@code custom_data.farmed_cultivating}, which Hypixel increments per crop
 * and syncs to the client. This reads a number the server already sent;
 * nothing is inferred from the player's actions.
 */
public final class CropRateTracker {
	private static final int SAMPLE_TICKS = 20;

	/** The rate window - long enough to smooth replant pauses. */
	private static final long WINDOW_MILLIS = 120_000;

	/** The readout goes quiet when the counter stops moving this long. */
	private static final long IDLE_MILLIS = 15_000;

	private record Sample(long at, long count) {
	}

	private static final ArrayDeque<Sample> samples = new ArrayDeque<>();
	private static int tickCounter;
	private static long lastMoveAt;

	private CropRateTracker() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (++tickCounter < SAMPLE_TICKS) {
				return;
			}

			tickCounter = 0;

			if (client.player == null || !ConfigManager.get().enabled) {
				samples.clear();
				return;
			}

			CustomData data = client.player.getMainHandItem()
					.get(DataComponents.CUSTOM_DATA);
			long count = -1;

			if (data != null && !data.isEmpty()) {
				var tag = data.copyTag();

				// Dump-verified 2026-08-25: farming tools count in
				// mined_crops; the old cultivating key stays as fallback.
				count = tag.getLongOr("mined_crops", -1);

				if (count < 0) {
					count = tag.getLongOr("farmed_cultivating", -1);
				}
			}
			long now = System.currentTimeMillis();

			if (count < 0) {
				// Not holding a counting tool. The samples stay so a brief
				// swap (killing a pest) does not zero the rate; the idle
				// timeout retires it if farming really stopped.
				trim(now);
				return;
			}

			Sample last = samples.peekLast();

			if (last != null && count < last.count()) {
				// A smaller counter is a different tool, not regress.
				samples.clear();
			}

			if (samples.isEmpty() || count != samples.peekLast().count()) {
				lastMoveAt = now;
			}

			samples.addLast(new Sample(now, count));
			trim(now);
		});
	}

	private static void trim(long now) {
		while (!samples.isEmpty() && now - samples.peekFirst().at() > WINDOW_MILLIS) {
			samples.removeFirst();
		}
	}

	/** "1.2k/min" while actively farming; empty when idle or unknown. */
	public static Optional<String> cropsPerMinute() {
		Sample first = samples.peekFirst();
		Sample last = samples.peekLast();
		long now = System.currentTimeMillis();

		if (first == null || last == null || last.at() - first.at() < 5_000
				|| last.count() <= first.count()
				|| now - lastMoveAt > IDLE_MILLIS) {
			return Optional.empty();
		}

		double perMinute = (last.count() - first.count()) * 60_000.0
				/ (last.at() - first.at());
		return Optional.of(dev.skyaid.parse.Numbers.shorten(
				Math.round(perMinute)) + "/min");
	}
}
