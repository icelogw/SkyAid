package dev.skyaid.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.skyaid.SkyAidClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and saves {@link Config} as JSON.
 *
 * <p>A broken or hand-edited config must never stop the game from starting, so a
 * parse failure falls back to defaults and logs rather than propagating.
 */
public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Config config = new Config();
	private static Path path;

	private ConfigManager() {
	}

	public static Config get() {
		return config;
	}

	public static void load() {
		path = FabricLoader.getInstance().getConfigDir().resolve(SkyAidClient.MOD_ID + ".json");

		if (!Files.exists(path)) {
			seedProfiles();
			save();
			return;
		}

		try {
			String json = Files.readString(path);
			Config loaded = GSON.fromJson(json, Config.class);

			// Gson returns null for an empty or "null" file rather than throwing.
			config = loaded != null ? loaded : new Config();
		} catch (IOException | RuntimeException e) {
			// Deliberately does not log the exception message: a malformed config
			// could contain the API key, and this line goes to latest.log.
			SkyAidClient.LOGGER.warn("Could not read {}, using defaults", path.getFileName());
			config = new Config();
		}

		seedProfiles();
	}

	/**
	 * The Catacombs profile is built in, like the standard layout: always
	 * present, never deletable, but fully editable. This restores it if a past
	 * version let it be removed, and marks an existing one as built-in.
	 */
	private static void seedProfiles() {
		Config.HudSettings hud = config.skyblockHud;
		boolean changed = seedProfile(hud, "The Catacombs",
				dev.skyaid.parse.HudLayout.catacombsOrder());
		changed |= seedProfile(hud, "The Garden",
				dev.skyaid.parse.HudLayout.gardenOrder());

		if (changed) {
			save();
		}
	}

	/** Ensures one shipped profile exists and stays marked built-in. */
	private static boolean seedProfile(
			Config.HudSettings hud, String zone, java.util.List<String> order) {
		for (Config.HudProfile profile : hud.profiles) {
			if (zone.equals(profile.zone)) {
				profile.builtin = true;
				return false;
			}
		}

		Config.HudProfile seeded = new Config.HudProfile();
		seeded.zone = zone;
		seeded.builtin = true;
		seeded.layout = new java.util.ArrayList<>(order);
		hud.profiles.add(seeded);
		return true;
	}

	public static void save() {
		if (path == null) {
			return;
		}

		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(config));
		} catch (IOException e) {
			SkyAidClient.LOGGER.warn("Could not write {}", path.getFileName());
		}
	}
}
