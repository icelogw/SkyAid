package dev.skyaid.core;

import com.google.gson.JsonObject;
import dev.skyaid.api.HypixelApiClient;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The current mayor (and minister, when there is one) from Hypixel's public,
 * keyless election resource. Refreshed hourly - elections move on a
 * multi-day cycle, so even that is generous.
 */
public final class MayorTracker {
	private static final long REFRESH_MILLIS = 60 * 60 * 1000L;

	private static volatile String mayorName;
	private static volatile long fetchedAt;
	private static final AtomicBoolean fetching = new AtomicBoolean();

	private MayorTracker() {
	}

	/** The "Mayor: Diana" text, fetching in the background when stale. */
	public static Optional<String> mayor() {
		if (System.currentTimeMillis() - fetchedAt > REFRESH_MILLIS
				&& fetching.compareAndSet(false, true)) {
			HypixelApiClient.get("/resources/skyblock/election", REFRESH_MILLIS, false)
					.whenComplete((body, error) -> {
						fetching.set(false);
						fetchedAt = System.currentTimeMillis();
						body.ifPresent(MayorTracker::read);
					});
		}

		return Optional.ofNullable(mayorName);
	}

	private static void read(JsonObject body) {
		JsonObject mayor = body.getAsJsonObject("mayor");

		if (mayor == null || !mayor.has("name")) {
			return;
		}

		String name = mayor.get("name").getAsString();

		// A minister rides along on some terms; show it when present.
		if (mayor.has("minister") && mayor.get("minister").isJsonObject()
				&& mayor.getAsJsonObject("minister").has("name")) {
			name += " (min. " + mayor.getAsJsonObject("minister")
					.get("name").getAsString() + ")";
		}

		mayorName = name;
	}
}
