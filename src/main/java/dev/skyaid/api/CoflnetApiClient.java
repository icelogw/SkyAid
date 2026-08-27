package dev.skyaid.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skyaid.SkyAidClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Lowest-BIN auction prices from Coflnet's public API - the community price
 * service the ecosystem leans on for the auction house, where Hypixel's own
 * API only offers a full ~100-page dump.
 *
 * <p>Strictly separated from {@link HypixelApiClient} on purpose: nothing but
 * the item id ever goes to this host - no API key, no player identity - and
 * the host pin makes that structural, not a convention.
 */
public final class CoflnetApiClient {
	private static final String API_HOST = "sky.coflnet.com";
	private static final String BASE_URL = "https://" + API_HOST + "/api";

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/** Fresh enough for shopping; stale prices still beat no prices. */
	private static final long TTL_MILLIS = 5 * 60_000;

	/** How long a failed lookup stays quiet before retrying. */
	private static final long NEGATIVE_TTL_MILLIS = 60_000;

	/** A throttled request is not a missing price; retry it soon. */
	private static final long THROTTLED_TTL_MILLIS = 8_000;

	/**
	 * At most this many requests in the air: a page flip wants ~12 prices at
	 * once, and firing them all triggered the service's rate limiting - the
	 * failures then read as "no price" and the page sat on question marks.
	 * Queued requests drain as slots free.
	 */
	private static final int MAX_CONCURRENT = 8;
	private static final java.util.concurrent.ConcurrentLinkedQueue<String> QUEUE =
			new java.util.concurrent.ConcurrentLinkedQueue<>();
	private static final java.util.concurrent.atomic.AtomicInteger ACTIVE =
			new java.util.concurrent.atomic.AtomicInteger();

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(TIMEOUT)
			.executor(Executors.newVirtualThreadPerTaskExecutor())
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/** item id -> {lowest BIN or -1 for a miss, fetched-at millis}. */
	private static final ConcurrentHashMap<String, long[]> CACHE = new ConcurrentHashMap<>();
	private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

	/**
	 * The cache persists across sessions: a day-old BIN is a fine placeholder
	 * while its refresh runs, and the museum browser reads hundreds of ids -
	 * rewarming them one by one every launch made every session start empty.
	 */
	private static final long PERSIST_MAX_AGE_MILLIS = 24 * 60 * 60_000L;
	private static final java.nio.file.Path CACHE_FILE =
			net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
					.resolve("skyaid-bin-cache.json");
	private static volatile long lastSavedAt;

	static {
		loadCache();
	}

	private static void loadCache() {
		try {
			if (!java.nio.file.Files.exists(CACHE_FILE)) {
				return;
			}

			JsonObject root = JsonParser.parseString(
					java.nio.file.Files.readString(CACHE_FILE)).getAsJsonObject();
			long now = System.currentTimeMillis();

			for (String id : root.keySet()) {
				var entry = root.getAsJsonArray(id);
				long at = entry.get(1).getAsLong();

				if (now - at < PERSIST_MAX_AGE_MILLIS
						&& id.matches("[A-Z0-9_;:-]{1,64}")) {
					CACHE.put(id, new long[]{entry.get(0).getAsLong(), at});
				}
			}
		} catch (Exception e) {
			SkyAidClient.LOGGER.warn("Could not load the BIN price cache");
		}
	}

	private static void saveCacheSoon() {
		long now = System.currentTimeMillis();

		if (now - lastSavedAt < 10_000) {
			return;
		}

		lastSavedAt = now;
		Thread.startVirtualThread(() -> {
			try {
				JsonObject root = new JsonObject();

				CACHE.forEach((id, entry) -> {
					// Real prices persist, and so do known-unlisted ids (-3):
					// both save a request next session.
					if (entry[0] >= 0 || entry[0] == -3) {
						var pair = new com.google.gson.JsonArray(2);
						pair.add(entry[0]);
						pair.add(entry[1]);
						root.add(id, pair);
					}
				});

				java.nio.file.Files.writeString(CACHE_FILE, root.toString());
			} catch (Exception e) {
				SkyAidClient.LOGGER.warn("Could not save the BIN price cache");
			}
		});
	}

	private CoflnetApiClient() {
	}

	/**
	 * The cached lowest BIN for an item, kicking off a background refresh when
	 * stale. Never blocks: the first ask for an item returns empty and the
	 * price appears once the fetch lands - fine for a tooltip redrawn every
	 * frame. A stale price is served while its refresh runs.
	 */
	public static OptionalLong cachedLowestBin(String itemId) {
		long[] entry = CACHE.get(itemId);
		long now = System.currentTimeMillis();

		// -2 throttled (retry soon), -3 the service does not list this id at
		// all (remember for hours - re-asking every minute ate the queue),
		// -1 an ordinary miss.
		long negativeTtl = entry == null ? NEGATIVE_TTL_MILLIS
				: entry[0] == -2 ? THROTTLED_TTL_MILLIS
						: entry[0] == -3 ? 6 * 60 * 60_000L : NEGATIVE_TTL_MILLIS;
		boolean fresh = entry != null && now - entry[1]
				< (entry[0] < 0 ? negativeTtl : TTL_MILLIS);

		if (!fresh) {
			fetch(itemId);
		}

		return entry == null || entry[0] < 0
				? OptionalLong.empty() : OptionalLong.of(entry[0]);
	}

	/**
	 * True once the service has ANSWERED for this id - a price, or a firm
	 * "not listed" (-3). Transient states (never asked, in flight, throttled,
	 * failed) are false. Lets a bulk pricing pass wait for the queue to drain
	 * without holding futures - queued fetches return pre-completed ones.
	 */
	public static boolean hasVerdict(String itemId) {
		long[] entry = CACHE.get(itemId);
		return entry != null && (entry[0] >= 0 || entry[0] == -3);
	}

	/** The cached lowest BIN WITHOUT triggering a fetch - for bulk ranking. */
	public static OptionalLong peekLowestBin(String itemId) {
		long[] entry = CACHE.get(itemId);
		return entry == null || entry[0] < 0
				? OptionalLong.empty() : OptionalLong.of(entry[0]);
	}

	/** The lowest BIN as a future, for command-style one-shot lookups. */
	public static CompletableFuture<OptionalLong> lowestBin(String itemId) {
		long[] entry = CACHE.get(itemId);
		long now = System.currentTimeMillis();

		if (entry != null && entry[0] >= 0 && now - entry[1] < TTL_MILLIS) {
			return CompletableFuture.completedFuture(OptionalLong.of(entry[0]));
		}

		return fetch(itemId).thenApply(ignored -> {
			long[] fetched = CACHE.get(itemId);
			return fetched == null || fetched[0] < 0
					? OptionalLong.empty() : OptionalLong.of(fetched[0]);
		});
	}

	/**
	 * Active auctions for an item, newest data first - price and end per
	 * listing. Uncached: asked once per command, not per frame.
	 */
	public static CompletableFuture<java.util.Optional<com.google.gson.JsonArray>>
			activeAuctions(String itemId) {
		return fetchArray("/auctions/tag/" + itemId + "/active/overview", itemId);
	}

	/** Recently ended auctions for an item - the most recent sale leads. */
	public static CompletableFuture<java.util.Optional<com.google.gson.JsonArray>>
			recentSales(String itemId) {
		return fetchArray("/auctions/tag/" + itemId + "/recent/overview", itemId);
	}

	/**
	 * A week of daily price aggregates for an item - {avg, min, max, time}
	 * entries. Feeds the "7d avg" line on /skyaid price; uncached, asked
	 * once per command.
	 */
	public static CompletableFuture<java.util.Optional<com.google.gson.JsonArray>>
			priceHistory(String itemId) {
		return fetchArray("/item/price/" + itemId + "/history/week", itemId);
	}

	/** Item search by human name: "superior dragon helmet" -> tagged items. */
	public static CompletableFuture<java.util.Optional<com.google.gson.JsonArray>>
			searchItems(String term) {
		String encoded = java.net.URLEncoder.encode(term.trim(),
				java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
		return fetchArray("/item/search/" + encoded, "SEARCH");
	}

	private static CompletableFuture<java.util.Optional<com.google.gson.JsonArray>>
			fetchArray(String path, String idForValidation) {
		if (!"SEARCH".equals(idForValidation)
				&& !idForValidation.matches("[A-Z0-9_;:-]{1,64}")) {
			return CompletableFuture.completedFuture(java.util.Optional.empty());
		}

		HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
				.timeout(TIMEOUT)
				.header("Accept", "application/json")
				.GET()
				.build();

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.handle((response, error) -> {
					if (error != null || response.statusCode() != 200) {
						return java.util.Optional.<com.google.gson.JsonArray>empty();
					}

					try {
						return java.util.Optional.of(
								JsonParser.parseString(response.body()).getAsJsonArray());
					} catch (RuntimeException e) {
						return java.util.Optional.<com.google.gson.JsonArray>empty();
					}
				});
	}

	private static CompletableFuture<Void> fetch(String itemId) {
		// Ids are Hypixel's own spelling; anything else does not go in a URL.
		if (!itemId.matches("[A-Z0-9_;:-]{1,64}") || !IN_FLIGHT.add(itemId)) {
			return CompletableFuture.completedFuture(null);
		}

		if (ACTIVE.get() >= MAX_CONCURRENT) {
			QUEUE.add(itemId);
			return CompletableFuture.completedFuture(null);
		}

		return fire(itemId);
	}

	private static CompletableFuture<Void> fire(String itemId) {
		ACTIVE.incrementAndGet();

		HttpRequest request = HttpRequest.newBuilder(
						URI.create(BASE_URL + "/item/price/" + itemId + "/bin"))
				.timeout(TIMEOUT)
				.header("Accept", "application/json")
				.GET()
				.build();

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.handle((response, error) -> {
					IN_FLIGHT.remove(itemId);
					long now = System.currentTimeMillis();

					if (error != null || response.statusCode() != 200) {
						if (error != null) {
							SkyAidClient.LOGGER.debug("Coflnet request failed ({})",
									error.getClass().getSimpleName());
						}

						// Throttling gets the short memory (-2); an id the
						// service rejects outright the long one (-3); other
						// failures the ordinary one (-1).
						int status = error == null ? response.statusCode() : 0;
						long marker = status == 429 ? -2
								: status == 400 || status == 404 ? -3 : -1;
						CACHE.put(itemId, new long[]{marker, now});

						if (marker == -3) {
							saveCacheSoon();
						}
					} else {
						try {
							JsonObject body = JsonParser.parseString(
									response.body()).getAsJsonObject();
							long lowest = body.has("lowest")
									? Math.round(body.get("lowest").getAsDouble()) : -1;
							CACHE.put(itemId, new long[]{lowest > 0 ? lowest : -1, now});

							if (lowest > 0) {
								saveCacheSoon();
							}
						} catch (RuntimeException e) {
							CACHE.put(itemId, new long[]{-1, now});
						}
					}

					ACTIVE.decrementAndGet();
					drainQueue();
					return null;
				});
	}

	private static void drainQueue() {
		while (ACTIVE.get() < MAX_CONCURRENT) {
			String next = QUEUE.poll();

			if (next == null) {
				return;
			}

			fire(next);
		}
	}
}
