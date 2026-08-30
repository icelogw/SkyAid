package dev.skyaid.feature;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.skyaid.parse.Numbers;
import dev.skyaid.parse.Skills;
import dev.skyaid.api.HypixelApiClient;
import dev.skyaid.api.MojangApiClient;
import dev.skyaid.core.HypixelDetector;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * A manual {@code /skyaid stats <player>} lookup against the Hypixel API.
 *
 * <p>Registered as a client command, so it never reaches the server. Lookups are
 * deliberately manual and one player at a time: an automatic overlay listing
 * everyone in a lobby is the most rules-sensitive form of this feature, and is
 * left out on purpose.
 *
 * <p>Only data the Hypixel API already publishes about a player is shown.
 */
public final class StatsLookup {
	/** Player data changes slowly; five minutes avoids re-requesting on retries. */
	private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;

	private StatsLookup() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("stats")
								// Without a player name this cannot match, and Minecraft would
								// forward it to Hypixel as an unknown command. Show usage instead.
								.executes(context -> {
									SkyAidHelp.show("/skyaid stats");
									return 1;
								})
								.then(ClientCommands.argument("player", StringArgumentType.word())
										.executes(context -> {
											lookup(context.getSource(),
													StringArgumentType.getString(context, "player"));
											return 1;
										})))));
	}

	private static void lookup(FabricClientCommandSource source, String name) {
		if (!HypixelDetector.isOnHypixel()) {
			error(source, "Only available while connected to Hypixel.");
			return;
		}

		if (!HypixelApiClient.hasApiKey()) {
			error(source, "No Hypixel API key set. Run /skyaid key add to paste one in.");
			return;
		}

		if (HypixelApiClient.keyLooksRejected()) {
			error(source, HypixelApiClient.hasUserKey()
					? "Hypixel is rejecting your API key - it has likely expired."
							+ " Get a new one and run /skyaid key add."
					: "Hypixel is rejecting SkyAid's built-in key right now."
							+ " Set your own with /skyaid key add.");
			return;
		}

		Optional<UUID> uuid = resolveFromTabList(name);

		if (uuid.isPresent()) {
			fetchStats(source, name, uuid.get());
			return;
		}

		// Not in this lobby - ask Mojang for the UUID instead. This costs one
		// public, credential-free request, and makes the command work for any
		// player rather than only whoever happens to share the server. No
		// progress chatter: the answer arrives within a second, and the user
		// asked for the in-between lines to go.
		MojangApiClient.resolve(name).thenAccept(resolved ->
				// Back to the client thread before touching chat.
				Minecraft.getInstance().execute(() -> {
					if (resolved.isEmpty()) {
						error(source, "Could not resolve " + name + " - no account by"
								+ " that name, or Mojang is unreachable.");
						return;
					}

					// Mojang returns the owner's exact casing; use it from here on.
					fetchStats(source, resolved.get().name(), resolved.get().uuid());
				}));
	}

	private static void fetchStats(FabricClientCommandSource source, String name, UUID uuid) {
		// The one progress line worth keeping, per the user: the "asking
		// Mojang" step went, this stays.
		source.sendFeedback(Component.literal("Looking up " + name + "...")
				.withStyle(ChatFormatting.GRAY));

		String id = uuid.toString().replace("-", "");

		// Karma lives on the network-wide player; everything else the user
		// asked for - Skyblock level, skills, coins - lives on the profile.
		var playerRequest = HypixelApiClient.get(
				"/player?uuid=" + id, CACHE_TTL_MILLIS, true);
		var profilesRequest = HypixelApiClient.get(
				"/skyblock/profiles?uuid=" + id, CACHE_TTL_MILLIS, true);

		// The museum is its own endpoint, addressed by profile id - its request
		// can only start once the profiles answer names the selected profile.
		var museumRequest = profilesRequest.thenCompose(profiles -> {
			JsonObject selected = selectedProfile(profiles);
			String profileId = selected != null && selected.has("profile_id")
					? selected.get("profile_id").getAsString() : null;

			return profileId == null
					? java.util.concurrent.CompletableFuture.completedFuture(
							Optional.<JsonObject>empty())
					: HypixelApiClient.get("/skyblock/museum?profile=" + profileId,
							CACHE_TTL_MILLIS, true);
		});

		playerRequest.thenCombine(profilesRequest, ProfilePair::new)
				.thenCombine(museumRequest, (pair, museum) -> {
					// Networth decodes every inventory blob - done here, off
					// the client thread, before hopping over to print.
					JsonObject member = selectedMember(pair.profiles(), id);
					JsonObject profile = selectedProfile(pair.profiles());
					JsonObject museumItems = museumMember(museum, id);

					Networth first = estimateNetworth(member, profile, museumItems);

					// The report goes out IMMEDIATELY - waiting on the AH
					// price queue held the whole answer for up to 25 seconds
					//. The unpriced kinds are
					// already queued; a refined networth line follows on its
					// own once they answer, the 7d-avg pattern.
					Minecraft.getInstance().execute(() -> report(
							source, name, id, pair.player(), pair.profiles(), first));

					if (first.unpriced().isEmpty()) {
						return null;
					}

					Thread.startVirtualThread(() -> {
						// Wait for verdicts, but bail early the moment the
						// queue stops making progress - a throttled service
						// is not worth staring at.
						long deadline = System.currentTimeMillis() + 15_000;
						long lastMissing = Long.MAX_VALUE;
						long lastProgressAt = System.currentTimeMillis();

						while (System.currentTimeMillis() < deadline) {
							try {
								Thread.sleep(500);
							} catch (InterruptedException e) {
								return;
							}

							long missing = first.unpriced().stream().filter(item ->
									!dev.skyaid.api.CoflnetApiClient.hasVerdict(item)).count();

							if (missing == 0) {
								break;
							}

							if (missing < lastMissing) {
								lastMissing = missing;
								lastProgressAt = System.currentTimeMillis();
							} else if (System.currentTimeMillis() - lastProgressAt > 4_000) {
								break;
							}
						}

						Networth second = estimateNetworth(member, profile, museumItems);

						// The report promised this line, so it always comes -
						// even a "same number, still N unpriced" answer beats
						// a promise that never lands.
						Minecraft.getInstance().execute(() -> {
							source.sendFeedback(Component.empty());
							source.sendFeedback(refinedLine(second, member));
							source.sendFeedback(Component.empty());
						});
					});
					return null;
				});
	}

	/** The looked-up player's own slice of the museum response, or null. */
	private static JsonObject museumMember(Optional<JsonObject> museum, String id) {
		if (museum.isEmpty() || !museum.get().has("members")
				|| !museum.get().get("members").isJsonObject()) {
			return null;
		}

		JsonObject members = museum.get().getAsJsonObject("members");
		return members.has(id) && members.get(id).isJsonObject()
				? members.getAsJsonObject(id) : null;
	}

	/**
	 * A base-price networth: purse and bank plus every item the profile
	 * exposes - inventories, sacks, essence, pets, museum - valued at bazaar
	 * insta-sell or lowest BIN. No enchant or modifier appraisal, so it reads
	 * deliberately as an estimate; item kinds without a live price are
	 * counted rather than silently zeroed. Per-category totals feed the
	 * hover breakdown, mirroring SkyCrypt's card.
	 */
	private record Networth(long value, java.util.Set<String> unpriced, boolean sawInventory,
			java.util.Map<String, Long> categories) {
		int unpricedKinds() {
			return unpriced.size();
		}
	}

	private static final long MAX_INVENTORY_NBT_BYTES = 8 * 1_048_576;

	/** API inventory keys -> the category names the breakdown shows. */
	private static final java.util.Map<String, String> INVENTORY_LABELS = java.util.Map.of(
			"inv_contents", "Inventory",
			"inv_armor", "Armor",
			"ender_chest_contents", "Ender Chest",
			"equipment_contents", "Equipment",
			"wardrobe_contents", "Wardrobe",
			"personal_vault_contents", "Personal Vault",
			"backpack_contents", "Storage",
			"backpack_icons", "Storage",
			"candy_inventory_contents", "Candy");

	private static final java.util.Map<String, String> BAG_LABELS = java.util.Map.of(
			"talisman_bag", "Accessories",
			"potion_bag", "Potion Bag",
			"fishing_bag", "Fishing Bag",
			"quiver", "Quiver",
			"sacks_bag", "Sacks Bag");

	private static Networth estimateNetworth(JsonObject member, JsonObject profile,
			JsonObject museum) {
		var categories = new java.util.LinkedHashMap<String, Long>();

		if (member == null) {
			return new Networth(0, java.util.Set.of(), false, categories);
		}

		java.util.Set<String> unpriced = new java.util.HashSet<>();
		categories.put("Purse + Bank", Math.round(purse(member) + bank(profile)));

		boolean sawInventory = false;
		JsonObject inventory = member.getAsJsonObject("inventory");

		if (inventory != null) {
			for (String key : inventory.keySet()) {
				if (key.equals("sacks_counts")) {
					continue; // plain id -> count map, priced below
				}

				JsonElement branch = inventory.get(key);
				sawInventory = true;

				if (key.equals("bag_contents") && branch.isJsonObject()) {
					for (String bag : branch.getAsJsonObject().keySet()) {
						addTo(categories, BAG_LABELS.getOrDefault(bag, "Bags"),
								blobValue(branch.getAsJsonObject().get(bag), 0, unpriced));
					}
					continue;
				}

				addTo(categories, INVENTORY_LABELS.getOrDefault(key, "Other"),
						blobValue(branch, 0, unpriced));
			}

			addTo(categories, "Sacks", sacksValue(inventory, unpriced));
		}

		addTo(categories, "Essence", essenceValue(member, unpriced));
		addTo(categories, "Pets", petsValue(member, unpriced));
		addTo(categories, "Museum", blobValue(museum, 0, unpriced));

		long total = categories.values().stream().mapToLong(Long::longValue).sum();
		return new Networth(total, unpriced, sawInventory, categories);
	}

	private static void addTo(java.util.Map<String, Long> categories, String label, long value) {
		if (value > 0) {
			categories.merge(label, value, Long::sum);
		}
	}

	/**
	 * Sums item value across every base64 "data" blob under the node - the
	 * museum's items/special subtrees nest deeper than the inventories, hence
	 * the generous depth cap.
	 */
	private static long blobValue(JsonElement node, int depth, java.util.Set<String> unpriced) {
		if (node == null || depth > 6) {
			return 0;
		}

		long total = 0;

		if (node.isJsonArray()) {
			for (JsonElement child : node.getAsJsonArray()) {
				total += blobValue(child, depth + 1, unpriced);
			}
			return total;
		}

		if (!node.isJsonObject()) {
			return 0;
		}

		for (var entry : node.getAsJsonObject().entrySet()) {
			if (entry.getKey().equals("data") && entry.getValue().isJsonPrimitive()) {
				total += itemsValue(entry.getValue().getAsString(), unpriced);
			} else {
				total += blobValue(entry.getValue(), depth + 1, unpriced);
			}
		}

		return total;
	}

	/** One compressed item list ("i"), valued at unit price plus modifiers. */
	private static long itemsValue(String base64, java.util.Set<String> unpriced) {
		long total = 0;

		try {
			var root = net.minecraft.nbt.NbtIo.readCompressed(
					new java.io.ByteArrayInputStream(
							java.util.Base64.getDecoder().decode(base64)),
					net.minecraft.nbt.NbtAccounter.create(MAX_INVENTORY_NBT_BYTES));
			var list = root.getListOrEmpty("i");

			for (int i = 0; i < list.size(); i++) {
				var item = list.getCompoundOrEmpty(i);
				var extra = item.getCompoundOrEmpty("tag")
						.getCompoundOrEmpty("ExtraAttributes");
				String itemId = extra.getString("id").orElse(null);

				if (itemId == null) {
					continue;
				}

				long count = item.getByteOr("Count", (byte) 1);
				var unit = PriceTooltips.sellValueById(itemId);

				if (unit.isPresent()) {
					total += unit.getAsLong() * count;
				} else {
					unpriced.add(itemId);
				}

				total += modifiersValue(extra);
			}
		} catch (Exception e) {
			// One undecodable blob must not sink the estimate.
		}

		return total;
	}

	/**
	 * The big value carriers ON an item, each priced as the material that
	 * applied it: enchant books, recombobulator, hot potato books, Art of
	 * War, gemstones. An unpriced modifier just adds nothing - it is not
	 * counted as "unpriced", or every fringe enchant would flood the note.
	 */
	private static long modifiersValue(net.minecraft.nbt.CompoundTag extra) {
		long total = 0;

		var enchantments = extra.getCompoundOrEmpty("enchantments");

		for (String name : enchantments.keySet()) {
			int level = enchantments.getIntOr(name, 0);

			if (level > 0) {
				total += PriceTooltips.sellValueById("ENCHANTMENT_"
						+ name.toUpperCase(Locale.ROOT) + "_" + level).orElse(0);
			}
		}

		if (extra.getIntOr("rarity_upgrades", 0) > 0) {
			total += PriceTooltips.sellValueById("RECOMBOBULATOR_3000").orElse(0);
		}

		int hotPotato = extra.getIntOr("hot_potato_count", 0);

		if (hotPotato > 0) {
			total += Math.min(hotPotato, 10)
					* PriceTooltips.sellValueById("HOT_POTATO_BOOK").orElse(0);
			total += Math.max(0, hotPotato - 10)
					* PriceTooltips.sellValueById("FUMING_POTATO_BOOK").orElse(0);
		}

		total += extra.getIntOr("art_of_war_count", 0)
				* PriceTooltips.sellValueById("THE_ART_OF_WAR").orElse(0);

		// Gems: {"JASPER_0": "FINE"} or {"JASPER_0": {quality: "FINE"}} ->
		// the applied gem item FINE_JASPER_GEM. Typed slots (COMBAT_0) name
		// their gem in a companion "COMBAT_0_gem" key. Slot-unlock keys skipped.
		var gems = extra.getCompoundOrEmpty("gems");

		for (String slot : gems.keySet()) {
			if (slot.endsWith("_gem") || slot.equals("unlocked_slots")) {
				continue;
			}

			String quality = gems.getString(slot).orElseGet(() ->
					gems.getCompoundOrEmpty(slot).getString("quality").orElse(null));
			String type = gems.getString(slot + "_gem")
					.orElseGet(() -> slot.replaceAll("_[0-9]+$", ""));

			if (quality != null) {
				total += PriceTooltips.sellValueById(
						quality + "_" + type + "_GEM").orElse(0);
			}
		}

		return total;
	}

	/** Sacks arrive as a plain item id -> count map - no NBT to decode. */
	private static long sacksValue(JsonObject inventory, java.util.Set<String> unpriced) {
		if (!inventory.has("sacks_counts") || !inventory.get("sacks_counts").isJsonObject()) {
			return 0;
		}

		long total = 0;
		JsonObject sacks = inventory.getAsJsonObject("sacks_counts");

		for (String id : sacks.keySet()) {
			long count = sacks.get(id).isJsonPrimitive() ? sacks.get(id).getAsLong() : 0;

			if (count <= 0) {
				continue;
			}

			var unit = PriceTooltips.sellValueById(id);

			if (unit.isPresent()) {
				total += unit.getAsLong() * count;
			} else {
				unpriced.add(id);
			}
		}

		return total;
	}

	/** Essence balances price straight off the bazaar (ESSENCE_<TYPE>). */
	private static long essenceValue(JsonObject member, java.util.Set<String> unpriced) {
		JsonObject currencies = member.getAsJsonObject("currencies");

		if (currencies == null || !currencies.has("essence")
				|| !currencies.get("essence").isJsonObject()) {
			return 0;
		}

		long total = 0;
		JsonObject essence = currencies.getAsJsonObject("essence");

		for (String type : essence.keySet()) {
			if (!essence.get(type).isJsonObject()) {
				continue;
			}

			long amount = essence.getAsJsonObject(type).has("current")
					? essence.getAsJsonObject(type).get("current").getAsLong() : 0;

			if (amount <= 0) {
				continue;
			}

			var unit = PriceTooltips.sellValueById("ESSENCE_" + type);

			if (unit.isPresent()) {
				total += unit.getAsLong() * amount;
			} else {
				unpriced.add("ESSENCE_" + type);
			}
		}

		return total;
	}

	/**
	 * Pets at the BIN of their type (PET_<TYPE>) plus any held item. Level,
	 * candy, and skins are modifiers, which base pricing ignores on purpose.
	 */
	private static long petsValue(JsonObject member, java.util.Set<String> unpriced) {
		JsonObject petsData = member.getAsJsonObject("pets_data");

		if (petsData == null || !petsData.has("pets") || !petsData.get("pets").isJsonArray()) {
			return 0;
		}

		long total = 0;

		for (JsonElement element : petsData.getAsJsonArray("pets")) {
			if (!element.isJsonObject()) {
				continue;
			}

			JsonObject pet = element.getAsJsonObject();

			if (pet.has("type") && pet.get("type").isJsonPrimitive()) {
				String tag = "PET_" + pet.get("type").getAsString();
				var unit = PriceTooltips.sellValueById(tag);

				if (unit.isPresent()) {
					total += unit.getAsLong();
				} else {
					unpriced.add(tag);
				}
			}

			if (pet.has("heldItem") && pet.get("heldItem").isJsonPrimitive()) {
				String held = pet.get("heldItem").getAsString();
				var unit = PriceTooltips.sellValueById(held);

				if (unit.isPresent()) {
					total += unit.getAsLong();
				} else {
					unpriced.add(held);
				}
			}
		}

		return total;
	}

	private record ProfilePair(Optional<JsonObject> player, Optional<JsonObject> profiles) {
	}

	/** The skills counted by the conventional "skill average", with their caps. */
	private static final String[][] AVERAGED_SKILLS = {
			{"SKILL_FARMING", "60"}, {"SKILL_MINING", "60"}, {"SKILL_COMBAT", "60"},
			{"SKILL_FORAGING", "50"}, {"SKILL_FISHING", "50"}, {"SKILL_ENCHANTING", "60"},
			{"SKILL_ALCHEMY", "50"}, {"SKILL_TAMING", "60"}};

	private static void report(FabricClientCommandSource source, String name, String id,
			Optional<JsonObject> playerResponse, Optional<JsonObject> profilesResponse,
			Networth networth) {
		if (playerResponse.isEmpty()) {
			error(source, "No stats returned for " + name
					+ " (API error, or the key is invalid).");
			return;
		}

		JsonObject player = playerResponse.get().getAsJsonObject("player");

		if (player == null) {
			error(source, name + " has never logged into Hypixel.");
			return;
		}

		JsonObject member = selectedMember(profilesResponse, id);
		JsonObject profile = selectedProfile(profilesResponse);

		source.sendFeedback(Component.empty());
		source.sendFeedback(Component.literal(name).withStyle(ChatFormatting.AQUA));
		source.sendFeedback(statLine("Skyblock level", skyblockLevel(member),
				ChatFormatting.GREEN));
		source.sendFeedback(statLine("Skill average", skillAverage(member),
				ChatFormatting.YELLOW));
		source.sendFeedback(statLine("Coins", coins(member, profile), ChatFormatting.GOLD));
		source.sendFeedback(networthLine(networth, member, true));
		source.sendFeedback(statLine("Karma", Numbers.group(asLong(player, "karma")),
				ChatFormatting.LIGHT_PURPLE));
		source.sendFeedback(Component.literal("  ")
				.append(link("[SkyCrypt]", "https://sky.shiiyu.moe/stats/" + name,
						ChatFormatting.AQUA))
				.append(Component.literal("  "))
				.append(link("[Plancke]", "https://plancke.io/hypixel/player/stats/" + name,
						ChatFormatting.GOLD)));
		source.sendFeedback(Component.empty());
	}

	private static Component statLine(String label, String value, ChatFormatting colour) {
		return Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(value).withStyle(colour));
	}

	/**
	 * The networth row. "~" and "base prices" are deliberate honesty: this is
	 * coins plus items at bazaar/BIN unit value, with no enchant, star, or pet
	 * appraisal - close enough to compare players, not an auction quote.
	 */
	private static Component networthLine(Networth networth, JsonObject member,
			boolean refining) {
		if (member == null || !networth.sawInventory()) {
			return statLine("Networth", "hidden (inventory API off)", ChatFormatting.DARK_GRAY);
		}

		// The SkyCrypt-style per-category card, largest first, as a hover.
		var breakdown = Component.literal("Networth (estimate)")
				.withStyle(ChatFormatting.GOLD);

		networth.categories().entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
				.forEach(entry -> breakdown
						.append(Component.literal("\n" + entry.getKey() + ": ")
								.withStyle(ChatFormatting.GRAY))
						.append(Component.literal(Numbers.shorten(entry.getValue()))
								.withStyle(ChatFormatting.GOLD)));

		if (networth.unpricedKinds() > 0) {
			breakdown.append(Component.literal("\n" + networth.unpricedKinds()
							+ " item kinds unpriced - rerun shortly to fill in")
					.withStyle(ChatFormatting.DARK_GRAY));
		}

		var hover = new net.minecraft.network.chat.HoverEvent.ShowText(breakdown);

		var line = Component.literal("  Networth: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal("~" + Numbers.shorten(networth.value()))
						.withStyle(ChatFormatting.GOLD))
				.append(Component.literal(" (estimate, hover for breakdown)")
						.withStyle(ChatFormatting.DARK_GRAY));

		if (networth.unpricedKinds() > 0) {
			line = line.append(Component.literal(refining
							? ", still pricing " + networth.unpricedKinds()
									+ " kinds - a refined line follows"
							: ", " + networth.unpricedKinds() + " kinds unpriced")
					.withStyle(ChatFormatting.DARK_GRAY));
		}

		return line.withStyle(style -> style.withHoverEvent(hover));
	}

	/** The follow-up networth once the price queue answered, same hover. */
	private static Component refinedLine(Networth networth, JsonObject member) {
		return networthLine(networth, member, false).copy()
				.append(Component.literal("  (refined)")
						.withStyle(ChatFormatting.DARK_GRAY));
	}

	/** The member entry of the currently selected profile, or null. */
	private static JsonObject selectedMember(Optional<JsonObject> profiles, String id) {
		JsonObject profile = selectedProfile(profiles);

		if (profile == null || !profile.has("members")) {
			return null;
		}

		JsonObject members = profile.getAsJsonObject("members");
		return members.has(id) ? members.getAsJsonObject(id) : null;
	}

	private static JsonObject selectedProfile(Optional<JsonObject> profiles) {
		if (profiles.isEmpty() || !profiles.get().has("profiles")
				|| !profiles.get().get("profiles").isJsonArray()) {
			return null;
		}

		for (JsonElement element : profiles.get().getAsJsonArray("profiles")) {
			JsonObject profile = element.getAsJsonObject();

			if (profile.has("selected") && profile.get("selected").getAsBoolean()) {
				return profile;
			}
		}

		return null;
	}

	/** Skyblock level: the profile's leveling experience, 100 XP per level. */
	private static String skyblockLevel(JsonObject member) {
		if (member == null || !member.has("leveling")) {
			return "unknown";
		}

		JsonObject leveling = member.getAsJsonObject("leveling");

		if (!leveling.has("experience")) {
			return "unknown";
		}

		return String.format(Locale.ROOT, "%.1f",
				leveling.get("experience").getAsDouble() / 100.0);
	}

	/** The average of the eight conventional skills, from raw XP via the curve. */
	private static String skillAverage(JsonObject member) {
		JsonObject experience = member == null ? null
				: member.has("player_data")
						? member.getAsJsonObject("player_data").getAsJsonObject("experience")
						: null;

		if (experience == null) {
			return "hidden";
		}

		double total = 0;

		for (String[] skill : AVERAGED_SKILLS) {
			double xp = experience.has(skill[0])
					? experience.get(skill[0]).getAsDouble()
					: 0;
			total += Skills.levelFor(xp, Integer.parseInt(skill[1]));
		}

		return String.format(Locale.ROOT, "%.1f", total / AVERAGED_SKILLS.length);
	}

	/** Purse plus bank, as the liquid-coins row above the networth estimate. */
	private static String coins(JsonObject member, JsonObject profile) {
		if (member == null) {
			return "unknown";
		}

		double bank = bank(profile);

		return Numbers.group(Math.round(purse(member) + bank))
				+ (bank == 0 ? " (purse; bank hidden)" : " (purse + bank)");
	}

	private static double purse(JsonObject member) {
		return member != null && member.has("currencies")
				&& member.getAsJsonObject("currencies").has("coin_purse")
						? member.getAsJsonObject("currencies").get("coin_purse").getAsDouble()
						: 0;
	}

	private static double bank(JsonObject profile) {
		return profile != null && profile.has("banking")
				&& profile.getAsJsonObject("banking").has("balance")
						? profile.getAsJsonObject("banking").get("balance").getAsDouble()
						: 0;
	}

	/**
	 * A clickable label opening the player's page on a community stats site.
	 * The name is safe to place in a URL: both resolution paths only accept
	 * 1-16 word characters.
	 */
	private static Component link(String label, String url, ChatFormatting colour) {
		return Component.literal(label).withStyle(style -> style
				.withColor(colour)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url))));
	}

	/**
	 * An error with a blank line either side, so it does not sit jammed against
	 * ordinary chat - the same breathing room the help list gets.
	 */
	private static void error(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.empty());
		source.sendError(Component.literal(message));
		source.sendFeedback(Component.empty());
	}

	private static long asLong(JsonObject object, String member) {
		return object.has(member) && object.get(member).isJsonPrimitive()
				? object.get(member).getAsLong()
				: 0L;
	}

	/**
	 * Resolves a name to a UUID from the tab list. Players in the lobby are already
	 * known to the client, so this costs no extra request.
	 */
	private static Optional<UUID> resolveFromTabList(String name) {
		var connection = Minecraft.getInstance().getConnection();

		if (connection == null) {
			return Optional.empty();
		}

		PlayerInfo info = connection.getPlayerInfoIgnoreCase(name);
		return info == null ? Optional.empty() : Optional.of(info.getProfile().id());
	}
}
