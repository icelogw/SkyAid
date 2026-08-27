package dev.skyaid.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skyaid.SkyAidClient;
import dev.skyaid.config.ConfigManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Talks to the Hypixel public API.
 *
 * <p>Everything is asynchronous and runs on virtual threads, so no request ever
 * blocks the client thread. Callers read from {@link #cache()} for a value to
 * draw right now and separately ask for a refresh.
 *
 * <p>Handling of the API key is deliberately strict, because it is the user's
 * credential:
 * <ul>
 *   <li>It is sent only to {@value #API_HOST}, enforced on every request.</li>
 *   <li>It goes in the API-Key header, never in a URL - URLs end up in logs.</li>
 *   <li>It is never logged, and no response or error text is logged verbatim.</li>
 * </ul>
 */
public final class HypixelApiClient {
	private static final String API_HOST = "api.hypixel.net";
	private static final String BASE_URL = "https://" + API_HOST + "/v2";

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(TIMEOUT)
			.executor(Executors.newVirtualThreadPerTaskExecutor())
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private static final ResponseCache CACHE = new ResponseCache();
	private static final RateLimiter LIMITER = new RateLimiter();

	private HypixelApiClient() {
	}

	public static ResponseCache cache() {
		return CACHE;
	}

	public static boolean hasApiKey() {
		return !ConfigManager.get().hypixelApiKey.isBlank();
	}

	/**
	 * Whether Hypixel's LAST answer rejected the stored key (expired or
	 * revoked - personal keys do expire). Lets features say "key invalid"
	 * instead of an eternal "Syncing...", which hid exactly this in the
	 * field. Cleared by the next accepted response.
	 */
	private static volatile boolean keyRejected;

	public static boolean keyLooksRejected() {
		return keyRejected;
	}

	/** Requests left in the current window per Hypixel, or -1 before any. */
	public static int rateBudgetRemaining() {
		return LIMITER.remainingBudget();
	}

	/** The outcome of checking the stored key, distinguishing the ways it can fail. */
	public enum KeyCheck {
		VALID,
		/** No key is stored. */
		NO_KEY,
		/** Hypixel answered, and said no. Only this one means the key is wrong. */
		REJECTED,
		/** Could not get an answer - offline, timed out, or rate limited. */
		UNREACHABLE
	}

	/**
	 * Checks the stored key against Hypixel.
	 *
	 * <p>Separate from {@link #get} because that collapses every failure into an
	 * empty result, which is fine for a HUD value but not here: telling somebody
	 * their key was rejected when the real problem was their internet would send
	 * them off regenerating a key that was never broken.
	 */
	public static CompletableFuture<KeyCheck> checkKey() {
		if (!hasApiKey()) {
			return CompletableFuture.completedFuture(KeyCheck.NO_KEY);
		}

		HttpRequest request;

		try {
			// Checked against a small authenticated endpoint that still exists:
			// the old /key introspection endpoint is gone from the v2 API, so a
			// VALID key got 404 there and read as "could not reach Hypixel"
			// while a wrong key got its 403 from the auth gate and read fine.
			request = buildRequest(BASE_URL + "/punishmentstats", true);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.completedFuture(KeyCheck.UNREACHABLE);
		}

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.handle((response, error) -> {
					if (error != null) {
						SkyAidClient.LOGGER.warn("Could not reach the Hypixel API ({})",
								error.getClass().getSimpleName());
						return KeyCheck.UNREACHABLE;
					}

					LIMITER.observe(response.headers());

					return switch (response.statusCode()) {
						case 200 -> succeeded(response.body()) ? KeyCheck.VALID : KeyCheck.REJECTED;
						case 401, 403 -> KeyCheck.REJECTED;
						default -> KeyCheck.UNREACHABLE;
					};
				});
	}

	/** A 200 can still carry {@code success: false}, which means the key was refused. */
	private static boolean succeeded(String body) {
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			return json.has("success") && json.get("success").getAsBoolean();
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * Fetches {@code path} (for example {@code "/player?uuid=..."}), caching the
	 * result for {@code ttlMillis}.
	 *
	 * @param requiresKey whether this endpoint needs the user's API key; keyless
	 *                    endpoints such as the bazaar still work with no key set
	 * @return the parsed body, or empty on any failure - callers degrade rather
	 *         than surface transport details
	 */
	public static CompletableFuture<Optional<JsonObject>> get(
			String path, long ttlMillis, boolean requiresKey) {
		String url = BASE_URL + path;

		Optional<JsonObject> cached = CACHE.get(url);

		if (cached.isPresent()) {
			return CompletableFuture.completedFuture(cached);
		}

		if (requiresKey && !hasApiKey()) {
			return CompletableFuture.completedFuture(Optional.empty());
		}

		if (!LIMITER.mayRequest()) {
			SkyAidClient.LOGGER.debug("Skipping Hypixel request: rate limit budget spent");
			return CompletableFuture.completedFuture(Optional.empty());
		}

		HttpRequest request;

		try {
			request = buildRequest(url, requiresKey);
		} catch (IllegalArgumentException e) {
			// Refuses to send the key anywhere but Hypixel.
			SkyAidClient.LOGGER.warn("Refusing malformed Hypixel API request");
			return CompletableFuture.completedFuture(Optional.empty());
		}

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.handle((response, error) -> {
					if (error != null) {
						// Class name only: an exception message can echo the request URL.
						SkyAidClient.LOGGER.warn("Hypixel API request failed ({})",
								error.getClass().getSimpleName());
						return Optional.<JsonObject>empty();
					}

					return handleResponse(response, url, ttlMillis);
				});
	}

	private static HttpRequest buildRequest(String url, boolean requiresKey) {
		URI uri = URI.create(url);

		// Belt and braces: the base URL is a constant, but this makes it impossible
		// for a future caller to smuggle the key to another host via the path.
		if (!API_HOST.equalsIgnoreCase(uri.getHost())) {
			throw new IllegalArgumentException("refusing non-Hypixel host");
		}

		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.timeout(TIMEOUT)
				.header("Accept", "application/json")
				.GET();

		if (requiresKey) {
			builder.header("API-Key", ConfigManager.get().hypixelApiKey);
		}

		return builder.build();
	}

	private static Optional<JsonObject> handleResponse(
			HttpResponse<String> response, String url, long ttlMillis) {
		LIMITER.observe(response.headers());

		int status = response.statusCode();

		if (status == 429) {
			LIMITER.onRateLimited(response.headers());
			SkyAidClient.LOGGER.warn("Hypixel API rate limit reached; backing off");
			return Optional.empty();
		}

		if (status == 401 || status == 403) {
			keyRejected = true;
			SkyAidClient.LOGGER.warn(
					"Hypixel API rejected the configured key (HTTP {})", status);
			return Optional.empty();
		}

		keyRejected = false;

		if (status != 200) {
			SkyAidClient.LOGGER.warn("Hypixel API returned HTTP {}", status);
			return Optional.empty();
		}

		try {
			JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();

			if (!body.has("success") || !body.get("success").getAsBoolean()) {
				return Optional.empty();
			}

			CACHE.put(url, body, ttlMillis);
			return Optional.of(body);
		} catch (RuntimeException e) {
			SkyAidClient.LOGGER.warn("Could not parse Hypixel API response");
			return Optional.empty();
		}
	}
}
