package dev.skyaid.api;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small TTL cache keyed by request URL.
 *
 * <p>Exists so the render thread never waits on the network: HUD and tooltip code
 * asks for whatever is cached right now and draws a placeholder on a miss, while
 * the refresh happens off-thread.
 */
public final class ResponseCache {
	private record Entry(JsonObject body, long expiresAtMillis) {
	}

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();

	public Optional<JsonObject> get(String key) {
		Entry entry = entries.get(key);

		if (entry == null) {
			return Optional.empty();
		}

		if (System.currentTimeMillis() >= entry.expiresAtMillis()) {
			entries.remove(key, entry);
			return Optional.empty();
		}

		return Optional.of(entry.body());
	}

	public void put(String key, JsonObject body, long ttlMillis) {
		entries.put(key, new Entry(body, System.currentTimeMillis() + ttlMillis));
	}

	public void clear() {
		entries.clear();
	}
}
