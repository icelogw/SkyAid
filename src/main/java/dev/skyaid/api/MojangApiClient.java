package dev.skyaid.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skyaid.SkyAidClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Resolves a player name to a UUID through Mojang's public profile endpoint,
 * for players who are not in the current lobby's tab list.
 *
 * <p>Nothing sensitive is involved: the only thing sent is the name the user
 * typed, to Mojang's own public API, with no credentials attached. The Hypixel
 * key never touches this class.
 *
 * <p>Results are cached: a name maps to the same UUID until its owner renames,
 * so ten minutes is comfortably safe and keeps repeated lookups of the same
 * player free.
 */
public final class MojangApiClient {
	private static final String API_HOST = "api.mojang.com";
	private static final String BASE_URL = "https://" + API_HOST + "/users/profiles/minecraft/";

	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final long CACHE_TTL_MILLIS = 10 * 60 * 1000L;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(TIMEOUT)
			.executor(Executors.newVirtualThreadPerTaskExecutor())
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private static final ResponseCache CACHE = new ResponseCache();

	/** A resolved account: the UUID, and the name in its owner's exact casing. */
	public record ResolvedPlayer(UUID uuid, String name) {
	}

	private MojangApiClient() {
	}

	/**
	 * Looks the name up, returning empty when no such account exists or Mojang
	 * cannot be reached - callers phrase the failure, not the transport.
	 */
	public static CompletableFuture<Optional<ResolvedPlayer>> resolve(String name) {
		// Names are 1-16 word characters. Refusing anything else keeps the value
		// safe to place in a URL without encoding.
		if (!isValidName(name)) {
			return CompletableFuture.completedFuture(Optional.empty());
		}

		String url = BASE_URL + name;
		Optional<JsonObject> cached = CACHE.get(url);

		if (cached.isPresent()) {
			return CompletableFuture.completedFuture(parse(cached.get()));
		}

		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(TIMEOUT)
				.header("Accept", "application/json")
				.GET()
				.build();

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.handle((response, error) -> {
					if (error != null) {
						SkyAidClient.LOGGER.warn("Mojang lookup failed ({})",
								error.getClass().getSimpleName());
						return Optional.empty();
					}

					if (response.statusCode() != 200) {
						// 404: no account by that name. Anything else is transport.
						return Optional.<ResolvedPlayer>empty();
					}

					try {
						JsonObject body = JsonParser.parseString(response.body())
								.getAsJsonObject();
						Optional<ResolvedPlayer> resolved = parse(body);

						if (resolved.isPresent()) {
							CACHE.put(url, body, CACHE_TTL_MILLIS);
						}

						return resolved;
					} catch (RuntimeException e) {
						SkyAidClient.LOGGER.warn("Could not parse Mojang response");
						return Optional.<ResolvedPlayer>empty();
					}
				});
	}

	/** Mojang sends the UUID as 32 bare hex digits; the dashes go back in here. */
	static Optional<ResolvedPlayer> parse(JsonObject body) {
		if (!body.has("id") || !body.has("name")) {
			return Optional.empty();
		}

		String id = body.get("id").getAsString();

		if (id.length() != 32) {
			return Optional.empty();
		}

		try {
			UUID uuid = UUID.fromString(id.substring(0, 8)
					+ "-" + id.substring(8, 12)
					+ "-" + id.substring(12, 16)
					+ "-" + id.substring(16, 20)
					+ "-" + id.substring(20));

			return Optional.of(new ResolvedPlayer(uuid, body.get("name").getAsString()));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	static boolean isValidName(String name) {
		if (name.isEmpty() || name.length() > 16) {
			return false;
		}

		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean word = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
					|| (c >= '0' && c <= '9') || c == '_';

			if (!word) {
				return false;
			}
		}

		return true;
	}
}
