package dev.skyaid.feature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real item appearances for the museum grid: each entry's actual Minecraft
 * item, and for the many custom-head items, the actual skin texture -
 * harvested once from the NotEnoughUpdates item repository (GPL lineage) and
 * bundled as a compact table. Anything the table misses falls back to the
 * keyword stand-in, so the grid never goes blank.
 */
public final class MuseumIcons {
	private static final Map<String, ItemStack> CACHE = new ConcurrentHashMap<>();
	private static volatile JsonObject data;
	private static volatile boolean loadTried;

	/** The handful of 1.8 item ids whose modern names differ. */
	private static final Map<String, String> LEGACY_RENAMES = Map.ofEntries(
			Map.entry("skull", "player_head"),
			Map.entry("wood_sword", "wooden_sword"),
			Map.entry("wood_axe", "wooden_axe"),
			Map.entry("wood_pickaxe", "wooden_pickaxe"),
			Map.entry("wood_shovel", "wooden_shovel"),
			Map.entry("wood_hoe", "wooden_hoe"),
			Map.entry("fishing_rod", "fishing_rod"),
			Map.entry("gold_sword", "golden_sword"),
			Map.entry("gold_axe", "golden_axe"),
			Map.entry("gold_pickaxe", "golden_pickaxe"),
			Map.entry("gold_shovel", "golden_shovel"),
			Map.entry("gold_hoe", "golden_hoe"),
			Map.entry("gold_helmet", "golden_helmet"),
			Map.entry("gold_chestplate", "golden_chestplate"),
			Map.entry("gold_leggings", "golden_leggings"),
			Map.entry("gold_boots", "golden_boots"),
			Map.entry("speckled_melon", "glistering_melon_slice"),
			Map.entry("fish", "cod"),
			Map.entry("dye", "ink_sac"),
			Map.entry("firework_charge", "firework_star"));

	private MuseumIcons() {
	}

	/** The display stack for a museum entry; never null, always cached. */
	public static ItemStack iconFor(String entryId, String prettyName) {
		return CACHE.computeIfAbsent(entryId, id -> {
			// Icon building runs on the render thread: whatever goes wrong
			// with one entry's data, the answer is a stand-in, not a crash.
			try {
				return build(id, prettyName);
			} catch (RuntimeException e) {
				dev.skyaid.SkyAidClient.LOGGER.warn(
						"Could not build a museum icon for {}", id);
				return MuseumBrowserScreen.iconFor(prettyName);
			}
		});
	}

	private static ItemStack build(String entryId, String prettyName) {
		ensureLoaded();
		JsonObject table = data;
		JsonObject record = table == null ? null : table.getAsJsonObject(entryId);

		if (record == null) {
			return MuseumBrowserScreen.iconFor(prettyName);
		}

		// A recorded skin texture means a custom head: embed the texture in a
		// profile component and the client renders the real skin, offline.
		// authlib 9's GameProfile is a record whose default property map is
		// IMMUTABLE - putting into it crashed the render thread - so the map
		// is built first and passed through the three-arg constructor.
		if (record.has("t")) {
			ItemStack head = new ItemStack(Items.PLAYER_HEAD);
			com.google.common.collect.Multimap<String, Property> textures =
					com.google.common.collect.LinkedHashMultimap.create();
			textures.put("textures",
					new Property("textures", record.get("t").getAsString()));
			GameProfile profile = new GameProfile(
					UUID.nameUUIDFromBytes(entryId.getBytes(StandardCharsets.UTF_8)),
					"museum",
					new com.mojang.authlib.properties.PropertyMap(textures));
			head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
			return head;
		}

		if (record.has("i")) {
			String raw = record.get("i").getAsString();
			String path = raw.startsWith("minecraft:") ? raw.substring(10) : raw;
			path = LEGACY_RENAMES.getOrDefault(path, path);
			Identifier id = Identifier.tryParse("minecraft:" + path);

			if (id != null) {
				Item item = BuiltInRegistries.ITEM.getValue(id);

				if (item != Items.AIR) {
					return new ItemStack(item);
				}
			}
		}

		return MuseumBrowserScreen.iconFor(prettyName);
	}

	private static synchronized void ensureLoaded() {
		if (loadTried) {
			return;
		}

		loadTried = true;

		try (InputStream stream = MuseumIcons.class.getResourceAsStream(
				"/assets/skyaid/museum-icons.json")) {
			if (stream == null) {
				return;
			}

			data = JsonParser.parseReader(new InputStreamReader(
					stream, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			dev.skyaid.SkyAidClient.LOGGER.warn("Could not load museum icons");
		}
	}
}
