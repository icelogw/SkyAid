package dev.skyaid.feature;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skyaid.api.HypixelApiClient;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The museum tracker: {@code /skyaid museum} reports donation progress per
 * wing and lists what is still missing, and item tooltips gain a "Museum:
 * needed" line for anything the player's museum still lacks.
 *
 * <p>The donatable-item list is bundled from the NotEnoughUpdates data
 * repository (GPL lineage, credited here); the player's own donations come
 * from Hypixel's museum endpoint using their API key, for their currently
 * selected profile. Set donations (armour) are recognised by set name and by
 * any of the set's pieces.
 */
public final class MuseumTracker {
	private static final long MUSEUM_TTL_MILLIS = 10 * 60_000;
	private static final long PROFILES_TTL_MILLIS = 10 * 60_000;
	private static final int MAX_MISSING_LISTED = 15;

	/** wing name -> donatable entries (item ids and armour set names). */
	private static volatile Map<String, Set<String>> wings;

	/** armour piece id -> its set name, reversed from sets_to_items. */
	private static volatile Map<String, String> pieceToSet;

	/** entry (item or set) -> museum XP it grants, from the bundled data. */
	private static volatile Map<String, Long> entryToXp;

	/** alternate item id (starred, renamed) -> its canonical museum id. */
	private static volatile Map<String, String> mappedIds;

	/** entry id -> Hypixel's exact display name, for searches and cards. */
	private static volatile Map<String, String> displayNames = Map.of();

	/** set name -> its piece ids, for pricing a whole set. */
	private static volatile Map<String, List<String>> setToPieces;

	/** Everything the player's museum already holds, by entry key. */
	private static volatile Set<String> donated;
	private static volatile long donatedFetchedAt;

	/**
	 * Entries the player was SEEN donating this session: a needed item that
	 * vanished from the inventory while a Museum screen was open went into
	 * the museum. Hypixel's API caches museum responses for minutes, so
	 * without this optimistic set a fresh donation reads "Not donated" until
	 * the server cache turns over. Cleared entry by entry once a real sync
	 * confirms them.
	 */
	private static final Set<String> localDonations =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static Map<String, Integer> museumInventorySnapshot;
	private static int museumWatchCounter;

	/**
	 * A drop must survive TWO consecutive scans before it counts as a
	 * deposit. Screen switches make the server clear-and-resend inventory
	 * slots, and a scan landing in that gap reads carried items as vanished
	 * - a
	 * blip is back by the next scan, a real deposit is not. Wrong marks
	 * also self-heal: an entry reappearing in the inventory unmarks itself.
	 */
	private static final Map<String, Integer> museumDropCandidates =
			new java.util.HashMap<>();
	private static long museumFlowLastSeen;

	/**
	 * Hypixel's server-driven GUI switches pass through a few ticks with NO
	 * screen open (Confirm Donation closing, Museum reopening). Forgetting
	 * the baseline the instant no museum screen shows erased it at exactly
	 * the moment the donation removed the items - the drop landed in the
	 * wiped gap. Only a real departure forgets.
	 */
	private static final long FLOW_GRACE_MILLIS = 5_000;

	private MuseumTracker() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("museum")
								.executes(context -> {
									report();
									return 1;
								})
								.then(ClientCommands.literal("value")
										.executes(context -> {
											valueReport();
											return 1;
										}))
								.then(ClientCommands.literal("browse")
										.executes(context -> {
											Minecraft.getInstance().execute(() ->
													Minecraft.getInstance().setScreenAndShow(
															new MuseumBrowserScreen()));
											return 1;
										})))));

		registerPanel();

		// The museum state syncs by itself: donations made before the mod was
		// ever installed are on Hypixel's side, so a quiet background fetch is
		// all it takes for tooltips to know them - no command, no re-donating.
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
				.register(client -> {
					// Closing a Museum menu means donations may just have
					// happened: mark the data stale so the next check (within
					// half a minute) refetches instead of waiting out the TTL.
					boolean museumScreenNow = client.gui != null
							&& client.gui.screen() != null
							&& client.gui.screen().getTitle().getString().contains("Museum");

					if (wasInMuseumScreen && !museumScreenNow) {
						donatedFetchedAt = 0;
					}

					wasInMuseumScreen = museumScreenNow;

					// While the museum flow is open, watch the inventory: a
					// needed item vanishing went INTO the museum, and marking
					// it donated locally keeps every list right immediately.
					watchMuseumDeposits(client);

					if (canSync(client)) {
						// Last session's donated set from disk: the grid is
						// usable the moment the museum opens, the fresh sync
						// corrects it seconds later.
						loadDonatedCache(client);

						// A museum screen waiting on data must not also wait
						// out the five-second cadence - sync right now.
						if (museumScreenNow && isStale()) {
							syncNow(client);
							return;
						}
					}

					if (++autoTickCounter < AUTO_CHECK_TICKS) {
						return;
					}

					autoTickCounter = 0;

					if (canSync(client) && isStale()) {
						syncNow(client);
					}
				});
	}

	private static boolean canSync(Minecraft client) {
		return dev.skyaid.config.ConfigManager.get().enabled
				&& HypixelApiClient.hasApiKey()
				&& client.player != null
				&& dev.skyaid.core.SkyblockTracker.state().inSkyblock();
	}

	private static boolean isStale() {
		return donated == null || System.currentTimeMillis()
				- donatedFetchedAt > MUSEUM_TTL_MILLIS;
	}

	/**
	 * One background sync; the in-flight flag keeps it to one at a time, and
	 * a 10s floor between ATTEMPTS keeps a failing fetch (dead key, outage)
	 * from being retried every tick - the field log showed 403s three times
	 * a second while the museum sat open.
	 */
	private static volatile long lastSyncAttemptMillis;

	private static void syncNow(Minecraft client) {
		long now = System.currentTimeMillis();

		if (now - lastSyncAttemptMillis < 10_000
				|| !autoFetching.compareAndSet(false, true)) {
			return;
		}

		lastSyncAttemptMillis = now;
		ensureListLoaded();
		String uuid = client.player.getUUID().toString().replace("-", "");

		fetch(uuid).whenComplete((museum, error) -> {
			autoFetching.set(false);

			if (error == null && museum.isPresent()) {
				Set<String> fresh = donatedEntries(museum.get(), uuid);
				localDonations.removeAll(fresh);
				donated = fresh;
				donatedFetchedAt = System.currentTimeMillis();
				saveDonatedCache(uuid, fresh);
				warmEverythingOnce();
			}
		});
	}

	/**
	 * The donated set persisted between sessions, so "Syncing..." shows an
	 * empty grid only on the very first run. Keyed by uuid - a different
	 * account's cache is ignored. donatedFetchedAt deliberately stays 0
	 * after a disk load: the head start renders, the real sync still fires.
	 */
	private static final java.nio.file.Path DONATED_CACHE_FILE =
			net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
					.resolve("skyaid-museum-cache.json");
	private static boolean donatedCacheLoaded;

	private static void loadDonatedCache(Minecraft client) {
		if (donatedCacheLoaded || client.player == null || donated != null) {
			return;
		}

		donatedCacheLoaded = true;

		try {
			if (!java.nio.file.Files.exists(DONATED_CACHE_FILE)) {
				return;
			}

			JsonObject root = com.google.gson.JsonParser.parseString(
					java.nio.file.Files.readString(DONATED_CACHE_FILE)).getAsJsonObject();
			String uuid = client.player.getUUID().toString().replace("-", "");

			if (!root.has("uuid") || !uuid.equals(root.get("uuid").getAsString())) {
				return;
			}

			Set<String> keys = new java.util.HashSet<>();
			root.getAsJsonArray("keys").forEach(key -> keys.add(key.getAsString()));
			donated = keys;
		} catch (Exception e) {
			// A broken cache file just means the usual first sync.
		}
	}

	private static void saveDonatedCache(String uuid, Set<String> keys) {
		Thread.startVirtualThread(() -> {
			try {
				JsonObject root = new JsonObject();
				root.addProperty("uuid", uuid);
				var array = new com.google.gson.JsonArray();
				keys.forEach(array::add);
				root.add("keys", array);
				java.nio.file.Files.writeString(DONATED_CACHE_FILE, root.toString());
			} catch (Exception e) {
				dev.skyaid.SkyAidClient.LOGGER.warn("Could not save the museum cache");
			}
		});
	}

	/**
	 * Every five seconds: the check itself is a few booleans, and the actual
	 * fetch is separately gated by TTL and in-flight state - so a fast cadence
	 * costs nothing and gets tooltips their data within seconds of logging in,
	 * no command required.
	 */
	private static final int AUTO_CHECK_TICKS = 100;
	private static int autoTickCounter;
	private static boolean wasInMuseumScreen;
	private static final java.util.concurrent.atomic.AtomicBoolean autoFetching =
			new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * The deposit watcher: snapshots the inventory's Skyblock ids twice a
	 * second while the museum FLOW is open - the Museum menus themselves AND
	 * the "Confirm Donation" menu, where the deposit actually happens
	 * (screenshot-verified title; Hypixel pre-places the items into it, so
	 * the vanish spans the screen switch and the baseline must SURVIVE
	 * in-flow switches). The AH results for a museum item also contain
	 * "Museum" in the title - excluded, buying there makes items APPEAR,
	 * not vanish. An id whose count dropped is a deposit candidate.
	 */
	private static void watchMuseumDeposits(Minecraft client) {
		String title = client.gui != null && client.gui.screen() != null
				? client.gui.screen().getTitle().getString() : "";

		// Deposits only ever happen in Hypixel's own CONTAINER menus. The
		// mod's museum browser also titles itself "Museum" but nothing can
		// leave the inventory there - counting it put the buy-a-piece trip
		// (browser -> F1 -> AH -> claim) inside the flow, and the claim's
		// inventory churn read as a deposit: ONE bought piece marked the
		// whole set donated.
		boolean inFlow = client.player != null
				&& client.gui != null
				&& client.gui.screen() instanceof net.minecraft.client.gui.screens
						.inventory.AbstractContainerScreen<?>
				&& !title.startsWith("Auction")
				&& (title.contains("Museum") || title.contains("Confirm Donation"));

		if (!inFlow) {
			if (museumInventorySnapshot != null && System.currentTimeMillis()
					- museumFlowLastSeen > FLOW_GRACE_MILLIS) {
				museumInventorySnapshot = null;
				museumDropCandidates.clear();
			}

			return;
		}

		museumFlowLastSeen = System.currentTimeMillis();

		// NBT-copying every slot is a twice-a-second job, not a per-tick one.
		if (++museumWatchCounter % 10 != 0) {
			return;
		}

		Map<String, Integer> current = new java.util.HashMap<>();
		var inventory = client.player.getInventory();

		for (int i = 0; i < inventory.getContainerSize(); i++) {
			String id = PriceTooltips.extractId(inventory.getItem(i));

			// Canonical keys, so the set-completeness check in markDeposited
			// can find alternate-id pieces still in hand.
			if (id != null) {
				current.merge(canonicalOf(id),
						inventory.getItem(i).getCount(), Integer::sum);
			}
		}

		// Self-healing: an entry back in the inventory was NOT donated after
		// all (a cancelled Confirm Donation hands the items back).
		if (!localDonations.isEmpty()) {
			for (String id : current.keySet()) {
				if (localDonations.remove(entryKeyOf(id))) {
					donatedFetchedAt = 0;
				}
			}
		}

		Map<String, Integer> previous = museumInventorySnapshot;
		museumInventorySnapshot = current;

		if (previous == null) {
			return;
		}

		// Candidates from the LAST scan: still gone now means a real deposit;
		// back to full strength means a sync blip, forgotten.
		for (var candidate : List.copyOf(museumDropCandidates.entrySet())) {
			museumDropCandidates.remove(candidate.getKey());

			if (current.getOrDefault(candidate.getKey(), 0) <= candidate.getValue()) {
				markDeposited(candidate.getKey(), current);
			}
		}

		for (Map.Entry<String, Integer> was : previous.entrySet()) {
			int now = current.getOrDefault(was.getKey(), 0);

			if (now < was.getValue()) {
				museumDropCandidates.put(was.getKey(), now);
			}
		}
	}

	/** An id that left the inventory mid-museum - mark its entry donated. */
	/** Alternate spellings collapse to one canonical id. */
	private static String canonicalOf(String itemId) {
		return mappedIds == null ? itemId : mappedIds.getOrDefault(itemId, itemId);
	}

	/** The museum entry an item counts toward: its set, else its canonical id. */
	private static String entryKeyOf(String itemId) {
		String canonical = canonicalOf(itemId);
		String set = pieceToSet == null ? null : pieceToSet.get(canonical);
		return set != null ? set : canonical;
	}

	private static void markDeposited(String itemId, Map<String, Integer> remaining) {
		Map<String, Set<String>> loadedWings = wings;
		Set<String> loadedDonated = donated;

		if (loadedWings == null) {
			return;
		}

		String canonical = canonicalOf(itemId);
		String set = pieceToSet == null ? null : pieceToSet.get(canonical);
		String key = set != null ? set : canonical;

		boolean donatable = loadedWings.values().stream()
				.anyMatch(wing -> wing.contains(key));

		if (!donatable || localDonations.contains(key)
				|| (loadedDonated != null && loadedDonated.contains(key))) {
			return;
		}

		// Armour goes in only as a FULL set: it counts as deposited once the
		// last piece has left the inventory.
		if (set != null && setToPieces != null) {
			for (String piece : setToPieces.getOrDefault(set, List.of())) {
				if (remaining.getOrDefault(piece, 0) > 0) {
					dev.skyaid.core.EventLog.event("museum", "vanish of " + itemId
							+ " NOT a set deposit - still holding " + piece);
					return;
				}
			}
		}

		dev.skyaid.core.EventLog.event("museum",
				"marked deposited: " + key + " (from vanish of " + itemId + ")");
		localDonations.add(key);
		donatedFetchedAt = 0; // and let the next sync confirm it from the API
	}

	/** Whose museum the optimistic/cached state belongs to. */
	private static volatile String lastIdentity;

	/** The profile-then-museum fetch chain, shared by command and background. */
	private static java.util.concurrent.CompletableFuture<Optional<JsonObject>> fetch(
			String uuid) {
		return HypixelApiClient.get("/skyblock/profiles?uuid=" + uuid,
						PROFILES_TTL_MILLIS, true)
				.thenCompose(profiles -> {
					String profileId = selectedProfileId(profiles);

					if (profileId == null) {
						return java.util.concurrent.CompletableFuture.completedFuture(
								Optional.<JsonObject>empty());
					}

					// A different account or profile invalidates everything
					// remembered about the previous one.
					String identity = uuid + "/" + profileId;

					if (!identity.equals(lastIdentity)) {
						if (lastIdentity != null) {
							localDonations.clear();
							donated = null;
							donatedFetchedAt = 0;
						}

						lastIdentity = identity;
					}

					return HypixelApiClient.get("/skyblock/museum?profile=" + profileId,
							MUSEUM_TTL_MILLIS, true);
				});
	}

	/**
	 * Tooltip verdict for an item id. For armour the museum takes the FULL
	 * SET, never a lone piece - a plain "not donated" on a single boot read
	 * as "you can donate this", which is false; the set name says the truth.
	 *
	 * @param needed   whether the museum still lacks this entry
	 * @param setName  the armour set the item belongs to, or null for items
	 *                 donatable on their own
	 * @param xp       the museum XP the donation grants, 0 when unknown
	 * @param setPieces the set's piece ids, empty for standalone items - so
	 *                  the tooltip can count how many the player is carrying
	 */
	public record Status(boolean needed, String setName, long xp,
			List<String> setPieces) {
	}

	public static Optional<Status> status(String itemId) {
		Map<String, Set<String>> loadedWings = wings;
		Set<String> loadedDonated = donated;

		if (loadedWings == null || loadedDonated == null) {
			return Optional.empty();
		}

		String canonical = canonicalOf(itemId);
		String set = pieceToSet == null ? null : pieceToSet.get(canonical);
		String key = set != null ? set : canonical;

		boolean donatable = false;

		for (Set<String> wing : loadedWings.values()) {
			if (wing.contains(key)) {
				donatable = true;
				break;
			}
		}

		if (!donatable) {
			return Optional.empty();
		}

		long xp = entryToXp == null ? 0 : entryToXp.getOrDefault(key, 0L);
		List<String> pieces = set == null || setToPieces == null
				? List.of() : setToPieces.getOrDefault(set, List.of());
		return Optional.of(new Status(
				!loadedDonated.contains(key) && !localDonations.contains(key),
				set == null ? null : pretty(set), xp, pieces));
	}

	/**
	 * {donated, total} for the HUD's progress line - present once both the
	 * museum list and a donations fetch have landed. Local deposits the API
	 * cache still lags are counted, same as the chat report.
	 */
	public static java.util.Optional<int[]> progress() {
		var loadedWings = wings;
		var loadedDonated = donated;

		if (loadedWings == null || loadedDonated == null) {
			return java.util.Optional.empty();
		}

		Set<String> owned = new java.util.HashSet<>(loadedDonated);
		owned.addAll(localDonations);

		int total = (int) loadedWings.values().stream().mapToLong(Set::size).sum();
		int have = (int) loadedWings.values().stream()
				.flatMap(Set::stream).filter(owned::contains).count();

		return java.util.Optional.of(new int[]{have, total});
	}

	private static void report() {
		say(Component.literal("Checking the museum...").withStyle(ChatFormatting.GRAY));
		ensureListLoaded();

		var player = Minecraft.getInstance().player;

		if (player == null || wings == null) {
			say(Component.literal("Museum data is not available right now.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		if (!HypixelApiClient.hasApiKey()) {
			say(Component.literal(
							"The museum needs your API key - set one with /skyaid key add.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		String uuid = player.getUUID().toString().replace("-", "");

		fetch(uuid).thenAccept(museum -> Minecraft.getInstance().execute(
				() -> reportMuseum(uuid, museum)));
	}

	private static void reportMuseum(String uuid, Optional<JsonObject> museum) {
		if (museum.isEmpty()) {
			say(Component.literal("Could not fetch your museum - key, profile or "
							+ "Hypixel may be the problem. Try again in a moment.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		Set<String> fresh = donatedEntries(museum.get(), uuid);
		localDonations.removeAll(fresh);
		donated = fresh;
		donatedFetchedAt = System.currentTimeMillis();

		// Counts include deposits seen locally that the API cache still lags.
		Set<String> owned = new java.util.HashSet<>(fresh);
		owned.addAll(localDonations);

		long totalDonatable = wings.values().stream().mapToLong(Set::size).sum();
		long totalDonated = wings.values().stream()
				.flatMap(Set::stream).filter(owned::contains).count();

		var message = Component.literal("Museum: ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(totalDonated + "/" + totalDonatable + " donated")
						.withStyle(ChatFormatting.GREEN));

		List<String> missing = new ArrayList<>();

		for (Map.Entry<String, Set<String>> wing : wings.entrySet()) {
			long has = wing.getValue().stream().filter(owned::contains).count();
			message = message.append(Component.literal(
							"\n  " + pretty(wing.getKey()) + ": ")
					.withStyle(ChatFormatting.GRAY)
					.append(Component.literal(has + "/" + wing.getValue().size())
							.withStyle(ChatFormatting.WHITE)));

			for (String entry : wing.getValue()) {
				if (!owned.contains(entry)) {
					missing.add(entry);
				}
			}
		}

		if (!missing.isEmpty()) {
			message = message.append(Component.literal("\nMissing next:")
					.withStyle(ChatFormatting.GRAY));

			for (int i = 0; i < Math.min(MAX_MISSING_LISTED, missing.size()); i++) {
				message = message.append(Component.literal("\n  " + pretty(missing.get(i)))
						.withStyle(ChatFormatting.YELLOW));
			}

			if (missing.size() > MAX_MISSING_LISTED) {
				message = message.append(Component.literal(
								"\n  ...and " + (missing.size() - MAX_MISSING_LISTED) + " more")
						.withStyle(ChatFormatting.DARK_GRAY));
			}
		}

		say(message);
	}

	/**
	 * The XP shopping list: every missing donatable entry priced (bazaar
	 * insta-buy or lowest BIN; sets as the sum of their pieces) and ranked by
	 * coins per museum XP, cheapest first. Entries without a live price or
	 * an XP value are left out rather than guessed.
	 */
	private static void valueReport() {
		ensureListLoaded();

		if (wings == null || entryToXp == null) {
			return;
		}

		Set<String> owned = donated;

		if (owned == null) {
			say(Component.literal(
							"Museum data is still syncing - try again in a moment.")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		List<Deal> deals = deals();
		int unpriced = dealsUnpriced;

		if (deals.isEmpty()) {
			say(Component.literal(unpriced > 0
							? "No priced donations to rank yet - prices are still loading."
							: "Nothing left to donate - the museum is full.")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		var message = Component.literal("Cheapest museum XP right now:")
				.withStyle(ChatFormatting.AQUA);

		for (int i = 0; i < Math.min(10, deals.size()); i++) {
			Deal deal = deals.get(i);
			long perXp = Math.round((double) deal.cost() / deal.xp());
			message = message
					.append(Component.literal("\n  " + pretty(deal.entry()) + ": ")
							.withStyle(ChatFormatting.WHITE))
					.append(Component.literal(dev.skyaid.parse.Numbers.shorten(deal.cost())
									+ " coins")
							.withStyle(ChatFormatting.GOLD))
					.append(Component.literal("  (" + dev.skyaid.parse.Numbers.group(perXp)
									+ "/xp, " + dev.skyaid.parse.Numbers.group(deal.xp()) + " xp)")
							.withStyle(ChatFormatting.DARK_GRAY));
		}

		if (unpriced > 0) {
			message = message.append(Component.literal(
							"\n  (" + unpriced + " missing items had no live price)")
					.withStyle(ChatFormatting.DARK_GRAY));
		}

		say(message);
	}

	/** One ranked donation deal, ready for panel or chat. */
	private record Deal(String entry, long cost, long xp) {
	}

	/** The ranked deals, cached a minute - prices barely move faster. */
	private static volatile List<Deal> dealsCache = List.of();
	private static volatile long dealsAt;
	private static volatile int dealsUnpriced;

	private static List<Deal> deals() {
		// While pricing is still warming up, retry fast; settle to a minute.
		long ttl = dealsCache.isEmpty() || dealsUnpriced > 0 ? 5_000 : 60_000;

		if (System.currentTimeMillis() - dealsAt > ttl) {
			dealsAt = System.currentTimeMillis();
			computeDeals();
		}

		return dealsCache;
	}

	/** How many BIN lookups one ranking pass may kick off - a trickle. */
	private static final int BIN_WARM_BUDGET = 6;

	private static void computeDeals() {
		Set<String> owned = donated;

		if (wings == null || entryToXp == null || owned == null) {
			dealsCache = List.of();
			return;
		}

		List<Deal> found = new ArrayList<>();
		int unpriced = 0;
		int warmBudget = BIN_WARM_BUDGET;

		for (Set<String> wing : wings.values()) {
			for (String entry : wing) {
				if (owned.contains(entry)) {
					continue;
				}

				Long xp = entryToXp.get(entry);

				if (xp == null || xp <= 0) {
					continue;
				}

				// Peek only: ranking must never stampede the price service.
				// A small per-pass budget warms missing prices instead, and
				// the fast retry above folds them in as they land.
				java.util.OptionalLong cost = costOf(entry);

				if (cost.isEmpty()) {
					unpriced++;

					if (warmBudget > 0) {
						warmBudget -= warm(entry);
					}

					continue;
				}

				found.add(new Deal(entry, cost.getAsLong(), xp));
			}
		}

		found.sort(java.util.Comparator.comparingDouble(
				deal -> (double) deal.cost() / deal.xp()));
		dealsUnpriced = unpriced;
		dealsCache = List.copyOf(found);
	}

	/**
	 * Priority warming for whatever the browser is SHOWING: a tile drawn with
	 * an unknown price asks for it right away. In-flight and negative caches
	 * make the per-frame calls free after the first.
	 */
	public static void warmEntry(String entry) {
		warm(entry);
	}

	private static volatile boolean warmedAll;

	/**
	 * One full warm-up per session, queued the moment museum data lands: the
	 * request queue drains the whole missing list in the background within a
	 * minute or so, the disk cache keeps the answers - after the first
	 * session, browsing is instant everywhere instead of per-page slow.
	 */
	private static void warmEverythingOnce() {
		if (warmedAll || wings == null) {
			return;
		}

		warmedAll = true;

		for (BrowserEntry entry : browserEntries()) {
			if (entry.cost() < 0) {
				warm(entry.id());
			}
		}
	}

	/** Kicks background fetches for an entry's unpriced ids; returns count. */
	private static int warm(String entry) {
		List<String> pieces = setToPieces == null ? null : setToPieces.get(entry);
		int fired = 0;

		for (String id : pieces == null ? List.of(entry) : pieces) {
			if (PriceTooltips.peekBuyPriceOf(id).isEmpty()) {
				dev.skyaid.api.CoflnetApiClient.cachedLowestBin(id);
				fired++;
			}
		}

		return Math.max(1, fired);
	}

	/**
	 * The side panel on Museum screens: the cheapest missing XP, drawn with
	 * the same extractor the HUD uses, beside Hypixel's own GUI.
	 */
	public static void registerPanel() {
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register(
				(client, screen, scaledWidth, scaledHeight) -> {
					String title = screen.getTitle().getString();

					// Museum screens only: an AH search for a museum item
					// titles its results "Auctions: \"Museum...\"" and got
					// the overlay drawn over it.
					if (!title.contains("Museum") || title.startsWith("Auction")
							|| !dev.skyaid.config.ConfigManager.get().enabled) {
						return;
					}

					net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
							.afterForeground(screen)
							.register((s, extractor, mouseX, mouseY, delta) ->
									drawOverlay(extractor, mouseX, mouseY));

					// The overlay's buttons work by click regions: sort and
					// wing cycles, page arrows. Clicks inside the overlay
					// never reach Hypixel's menu underneath.
					net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
							.allowMouseClick(screen)
							.register((s, mouse) -> !overlayClick(
									client, (int) mouse.x(), (int) mouse.y()));

					// The search field's typing, captured while focused so
					// inventory keys do not fire mid-word.
					net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
							.allowCharType(screen)
							.register((s, character) -> !overlayCharTyped(
									character.codepointAsString()));
					net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
							.allowKeyPress(screen)
							.register((s, key) -> !overlayKeyPressed(key.key()));
				});
	}

	/** Vanilla menu palette: grey body, bevelled edges, dark label text. */
	private static final int PANEL_BODY = 0xFFC6C6C6;
	private static final int PANEL_LIGHT = 0xFFFFFFFF;
	private static final int PANEL_DARK = 0xFF555555;
	private static final int PANEL_BORDER = 0xFF000000;
	private static final int LABEL = 0xFF404040;
	private static final int SLOT = 0xFF8B8B8B;

	/** Vanilla's bright chat green - with a shadow it reads on any ground. */
	private static final int GREEN = 0xFF54FC54;

	/** Centered text with an EXPLICIT shadow choice: the extractor's own
	 * centeredText always shadows, and a black shadow under dark text on the
	 * grey panel body is exactly the double-print mush it produced. */
	private static void shadowCentered(
			net.minecraft.client.gui.GuiGraphicsExtractor extractor,
			net.minecraft.client.gui.Font font, String text, int centerX, int y,
			int colour) {
		extractor.text(font, text, centerX - font.width(text) / 2, y, colour, true);
	}

	private static void plainCentered(
			net.minecraft.client.gui.GuiGraphicsExtractor extractor,
			net.minecraft.client.gui.Font font, String text, int centerX, int y,
			int colour) {
		extractor.text(font, text, centerX - font.width(text) / 2, y, colour, false);
	}

	/** The overlay grid, right of the museum GUI: user-designed browser. */
	private static final int OVERLAY_COLS = 4;
	private static final int OVERLAY_ROWS = 4;
	private static final int OVERLAY_TILE = 40;
	private static final String[] SORT_LABELS =
			{"Coins/XP Low", "Coins/XP High", "Cost", "XP"};

	private static int overlaySort;
	private static int overlayWing = -1;
	private static int overlayPage;
	private static String overlayQuery = "";
	private static boolean overlaySearchFocused;

	private static int overlayLeft() {
		int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int panelWidth = OVERLAY_COLS * OVERLAY_TILE + 12;

		// Beside the GUI when it fits, clamped to the screen edge when not.
		return Math.min(width / 2 + 88 + 8, width - panelWidth - 8);
	}

	private static int overlayHeight() {
		return 44 + OVERLAY_ROWS * OVERLAY_TILE + 34;
	}

	private static int overlayTop() {
		return (Minecraft.getInstance().getWindow().getGuiScaledHeight()
				- overlayHeight()) / 2;
	}

	/** The grid re-ranks once a second, not once a frame - 600 entries with
	 * price lookups and a sort were real per-frame work. */
	private static List<BrowserEntry> overlayEntriesCache = List.of();
	private static long overlayEntriesAt;

	private static List<BrowserEntry> overlayEntries() {
		if (System.currentTimeMillis() - overlayEntriesAt < 1_000) {
			return overlayEntriesCache;
		}

		overlayEntriesAt = System.currentTimeMillis();
		overlayEntriesCache = computeOverlayEntries();
		return overlayEntriesCache;
	}

	private static List<BrowserEntry> computeOverlayEntries() {
		List<BrowserEntry> all = browserEntries();
		List<String> wings = wingNames();
		String wing = overlayWing < 0 || overlayWing >= wings.size()
				? null : wings.get(overlayWing);
		List<BrowserEntry> result = new ArrayList<>();

		String query = overlayQuery.toLowerCase(Locale.ROOT);

		for (BrowserEntry entry : all) {
			if ((wing == null || entry.wing().equals(wing))
					&& (query.isEmpty() || entry.name()
							.toLowerCase(Locale.ROOT).contains(query))) {
				result.add(entry);
			}
		}

		result.sort((a, b) -> switch (overlaySort) {
			// High direction: unpriced entries map to -1 so they still sink.
			case 1 -> Double.compare(
					b.cost() < 0 || b.xp() <= 0 ? -1 : (double) b.cost() / b.xp(),
					a.cost() < 0 || a.xp() <= 0 ? -1 : (double) a.cost() / a.xp());
			case 2 -> Long.compare(a.cost() < 0 ? Long.MAX_VALUE : a.cost(),
					b.cost() < 0 ? Long.MAX_VALUE : b.cost());
			case 3 -> Long.compare(b.xp(), a.xp());
			default -> Double.compare(
					a.cost() < 0 || a.xp() <= 0 ? Double.MAX_VALUE
							: (double) a.cost() / a.xp(),
					b.cost() < 0 || b.xp() <= 0 ? Double.MAX_VALUE
							: (double) b.cost() / b.xp());
		});

		return result;
	}

	private static void drawOverlay(
			net.minecraft.client.gui.GuiGraphicsExtractor extractor,
			int mouseX, int mouseY) {
		deals(); // Keeps the price warming trickle alive while browsing.

		var font = Minecraft.getInstance().font;
		int x = overlayLeft();
		int y = overlayTop();
		int width = OVERLAY_COLS * OVERLAY_TILE + 12;
		int height = overlayHeight();

		extractor.fill(x - 3, y - 3, x + width + 3, y + height + 3, PANEL_BORDER);
		extractor.fill(x - 2, y - 2, x + width + 2, y + height + 2, PANEL_LIGHT);
		extractor.fill(x, y, x + width + 2, y + height + 2, PANEL_DARK);
		extractor.fill(x - 1, y - 1, x + width + 1, y + height + 1, PANEL_BODY);

		// The search field: a vanilla sunken text box; click to focus, type
		// to filter, escape or clicking elsewhere lets go.
		extractor.fill(x + 6, y + 6, x + width - 6, y + 20, PANEL_BORDER);
		extractor.fill(x + 7, y + 7, x + width - 7, y + 19, 0xFF000000);

		if (overlayQuery.isEmpty() && !overlaySearchFocused) {
			extractor.text(font, "Search...", x + 10, y + 9, 0xFF808080, true);
		} else {
			extractor.text(font, overlayQuery
							+ (overlaySearchFocused ? "_" : ""),
					x + 10, y + 9, 0xFFFFFFFF, true);
		}

		// Header buttons: sort cycle and wing cycle, bevelled like vanilla.
		drawButton(extractor, font, x + 6, y + 24,
				(width - 16) / 2, SORT_LABELS[overlaySort]);
		List<String> wings = wingNames();
		String wingLabel = overlayWing < 0 || overlayWing >= wings.size()
				? "All wings" : prettyWingName(wings.get(overlayWing));
		drawButton(extractor, font, x + 6 + (width - 16) / 2 + 4, y + 24,
				(width - 16) / 2, wingLabel);

		List<BrowserEntry> entries = overlayEntries();
		int pageSize = OVERLAY_COLS * OVERLAY_ROWS;
		int maxPage = Math.max(0, (entries.size() - 1) / pageSize);
		overlayPage = Math.min(overlayPage, maxPage);

		BrowserEntry hovered = null;
		int gridTop = y + 44;

		for (int i = 0; i < pageSize; i++) {
			int index = overlayPage * pageSize + i;
			int tileX = x + 6 + (i % OVERLAY_COLS) * OVERLAY_TILE;
			int tileY = gridTop + (i / OVERLAY_COLS) * OVERLAY_TILE;

			extractor.fill(tileX, tileY, tileX + OVERLAY_TILE - 4,
					tileY + OVERLAY_TILE - 4, 0xFF373737);
			extractor.fill(tileX + 1, tileY + 1, tileX + OVERLAY_TILE - 4,
					tileY + OVERLAY_TILE - 4, PANEL_LIGHT);
			extractor.fill(tileX + 1, tileY + 1, tileX + OVERLAY_TILE - 5,
					tileY + OVERLAY_TILE - 5, SLOT);

			if (index >= entries.size()) {
				continue;
			}

			BrowserEntry entry = entries.get(index);

			if (entry.cost() < 0) {
				warmEntry(entry.id());
			}

			extractor.item(MuseumIcons.iconFor(entry.id(), entry.name()),
					tileX + 10, tileY + 4);
			shadowCentered(extractor, font, overlayHeadline(entry),
					tileX + (OVERLAY_TILE - 4) / 2, tileY + 24, GREEN);

			if (mouseX >= tileX && mouseX < tileX + OVERLAY_TILE - 4
					&& mouseY >= tileY && mouseY < tileY + OVERLAY_TILE - 4) {
				hovered = entry;
				// The vanilla slot-hover highlight: translucent white square.
				extractor.fill(tileX + 1, tileY + 1, tileX + OVERLAY_TILE - 5,
						tileY + OVERLAY_TILE - 5, 0x80FFFFFF);
			}
		}

		int footerY = gridTop + OVERLAY_ROWS * OVERLAY_TILE + 2;
		drawButton(extractor, font, x + 6, footerY, 18, "<");
		drawButton(extractor, font, x + width - 18 - 4, footerY, 18, ">");
		// Body text follows the vanilla rule: dark and UNSHADOWED on the
		// light panel - a shadow there smears grey onto grey.
		plainCentered(extractor, font,
				"Page " + (overlayPage + 1) + "/" + (maxPage + 1),
				x + width / 2, footerY + 4, LABEL);

		plainCentered(extractor, font,
				"Green: " + switch (overlaySort) {
					case 2 -> "total cost";
					case 3 -> "museum XP";
					default -> "coins per XP";
				},
				x + width / 2, footerY + 18, LABEL);

		if (entries.isEmpty()) {
			// An expired key must SAY so - "Syncing..." hid a dead key for a
			// whole session in the field.
			if (HypixelApiClient.keyLooksRejected()) {
				plainCentered(extractor, font, "API key invalid or expired",
						x + width / 2, gridTop + 24, 0xFFAA2222);
				plainCentered(extractor, font, "/skyaid key add",
						x + width / 2, gridTop + 36, 0xFFAA2222);
			} else {
				plainCentered(extractor, font,
						donated == null ? "Syncing..." : "Nothing left here",
						x + width / 2, gridTop + 30, LABEL);
			}
		}

		overlayHoveredName = hovered == null ? null : hovered.name();

		if (hovered != null) {
			drawHoverCard(extractor, font, mouseX, mouseY, hovered);
		}
	}

	/** The overlay tile under the mouse, so F1/F2 search the RIGHT item -
	 * grid tiles are not real item stacks and never reach the tooltip path. */
	private static volatile String overlayHoveredName;

	public static String overlayHoveredName() {
		return overlayHoveredName;
	}

	private static void drawButton(
			net.minecraft.client.gui.GuiGraphicsExtractor extractor,
			net.minecraft.client.gui.Font font, int x, int y, int width, String label) {
		int height = 16;
		extractor.fill(x, y, x + width, y + height, PANEL_BORDER);
		extractor.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL_LIGHT);
		extractor.fill(x + 2, y + 2, x + width - 1, y + height - 1, PANEL_DARK);
		extractor.fill(x + 2, y + 2, x + width - 2, y + height - 2, 0xFF8B8B8B);
		// White with shadow, exactly like vanilla's own buttons.
		shadowCentered(extractor, font, label, x + width / 2, y + 4, 0xFFFFFFFF);
	}

	private static String overlayHeadline(BrowserEntry entry) {
		return switch (overlaySort) {
			case 2 -> entry.cost() < 0 ? "?"
					: dev.skyaid.parse.Numbers.shorten(entry.cost());
			case 3 -> Long.toString(entry.xp());
			default -> entry.cost() < 0 || entry.xp() <= 0 ? "?"
					: dev.skyaid.parse.Numbers.shorten(
							Math.round((double) entry.cost() / entry.xp()));
		};
	}

	private static void drawHoverCard(
			net.minecraft.client.gui.GuiGraphicsExtractor extractor,
			net.minecraft.client.gui.Font font, int mouseX, int mouseY,
			BrowserEntry entry) {
		List<String> lines = new ArrayList<>();
		lines.add(entry.name());
		lines.add("Wing: " + prettyWingName(entry.wing()));

		// Armour entries are whole sets; say what the set contains.
		List<String> pieces = setToPieces == null ? null : setToPieces.get(entry.id());

		if (pieces != null) {
			lines.add("Full set of " + pieces.size() + " pieces");
		}

		lines.add(entry.cost() >= 0
				? "Cost: " + dev.skyaid.parse.Numbers.group(entry.cost()) + " coins"
				: "Cost: no live price yet");

		if (entry.xp() > 0) {
			lines.add("Reward: +" + entry.xp() + " XP");

			if (entry.cost() >= 0) {
				lines.add(dev.skyaid.parse.Numbers.shorten(Math.round(
						(double) entry.cost() / entry.xp())) + " coins per XP");
			}
		}

		lines.add("");
		lines.add("F1: search AH   F2/F4: bazaar");

		drawVanillaTooltip(extractor, font, mouseX, mouseY, lines);
	}

	/**
	 * The real vanilla tooltip look: near-black purple-tinted body with the
	 * purple gradient border - so the card reads as a native tooltip, not a
	 * mod rectangle.
	 */
	static void drawVanillaTooltip(
			net.minecraft.client.gui.GuiGraphicsExtractor extractor,
			net.minecraft.client.gui.Font font, int mouseX, int mouseY,
			List<String> lines) {
		int boxWidth = 0;

		for (String line : lines) {
			boxWidth = Math.max(boxWidth, font.width(line));
		}

		int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int x = Math.min(mouseX + 10, screenWidth - boxWidth - 12);
		int y = mouseY + 6;
		int height = lines.size() * 11;

		int background = 0xF0100010;
		int borderTop = 0x505000FF;
		int borderBottom = 0x5028007F;

		// Body plus the one-pixel purple frame, vanilla's exact recipe.
		extractor.fill(x - 4, y - 5, x + boxWidth + 4, y - 4, background);
		extractor.fill(x - 4, y + height + 3, x + boxWidth + 4, y + height + 4, background);
		extractor.fill(x - 4, y - 4, x + boxWidth + 4, y + height + 3, background);
		extractor.fill(x - 5, y - 4, x - 4, y + height + 3, background);
		extractor.fill(x + boxWidth + 4, y - 4, x + boxWidth + 5, y + height + 3, background);
		extractor.fill(x - 4, y - 3, x - 3, y + height + 2, borderTop);
		extractor.fill(x + boxWidth + 3, y - 3, x + boxWidth + 4, y + height + 2, borderBottom);
		extractor.fill(x - 4, y - 4, x + boxWidth + 4, y - 3, borderTop);
		extractor.fill(x - 4, y + height + 2, x + boxWidth + 4, y + height + 3, borderBottom);

		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).isEmpty()) {
				continue;
			}

			int colour = i == 0 ? 0xFF55FFFF
					: i == lines.size() - 1 ? 0xFF808080 : 0xFFCCCCCC;
			extractor.text(font, lines.get(i), x, y + i * 11, colour, true);
		}
	}

	/** Click routing for the overlay; true when the click was ours. */
	private static boolean overlayClick(Minecraft client, int mouseX, int mouseY) {
		int x = overlayLeft();
		int y = overlayTop();
		int width = OVERLAY_COLS * OVERLAY_TILE + 12;
		int height = overlayHeight();

		if (mouseX < x - 3 || mouseX > x + width + 3
				|| mouseY < y - 3 || mouseY > y + height + 3) {
			overlaySearchFocused = false;
			return false;
		}

		// The search field claims focus on click; anywhere else lets it go.
		if (mouseY >= y + 6 && mouseY < y + 20) {
			overlaySearchFocused = true;
			return true;
		}

		overlaySearchFocused = false;

		int half = (width - 16) / 2;

		if (mouseY >= y + 24 && mouseY < y + 40) {
			if (mouseX >= x + 6 && mouseX < x + 6 + half) {
				overlaySort = (overlaySort + 1) % SORT_LABELS.length;
			} else if (mouseX >= x + 6 + half + 4) {
				List<String> wings = wingNames();
				overlayWing = overlayWing + 1 >= wings.size() ? -1 : overlayWing + 1;
				overlayPage = 0;
			}

			overlayEntriesAt = 0; // Re-rank immediately on a control click.
			return true;
		}

		int footerY = y + 44 + OVERLAY_ROWS * OVERLAY_TILE + 2;

		if (mouseY >= footerY && mouseY < footerY + 16) {
			if (mouseX < x + 6 + 18) {
				overlayPage = Math.max(0, overlayPage - 1);
			} else if (mouseX >= x + width - 22) {
				overlayPage++;
			}
		}

		return true;
	}

	/** Typed characters land in the focused search field. */
	private static boolean overlayCharTyped(String character) {
		if (!overlaySearchFocused) {
			return false;
		}

		if (overlayQuery.length() < 32) {
			overlayQuery += character;
			overlayPage = 0;
			overlayEntriesAt = 0;
		}

		return true;
	}

	/** Backspace and escape handling for the focused search field. */
	private static boolean overlayKeyPressed(int key) {
		if (!overlaySearchFocused) {
			return false;
		}

		if (key == 259 && !overlayQuery.isEmpty()) { // backspace
			overlayQuery = overlayQuery.substring(0, overlayQuery.length() - 1);
			overlayPage = 0;
			overlayEntriesAt = 0;
			return true;
		}

		if (key == 256) { // escape releases focus but keeps the filter
			overlaySearchFocused = false;
			return true;
		}

		// While typing, every key belongs to the field - E must not close
		// the museum mid-word.
		return true;
	}

	private static String prettyWingName(String wing) {
		return wing.substring(0, 1).toUpperCase(Locale.ROOT) + wing.substring(1);
	}

	/** One missing donation for the browser grid; cost -1 while unpriced. */
	public record BrowserEntry(String id, String name, String wing, long xp, long cost) {
	}

	/** Every missing donation, prices peeked (never fetched) - browser data. */
	public static List<BrowserEntry> browserEntries() {
		Set<String> owned = donated;

		if (wings == null || owned == null) {
			return List.of();
		}

		List<BrowserEntry> entries = new ArrayList<>();

		for (Map.Entry<String, Set<String>> wing : wings.entrySet()) {
			for (String entry : wing.getValue()) {
				if (owned.contains(entry) || localDonations.contains(entry)) {
					continue;
				}

				long xp = entryToXp == null ? 0 : entryToXp.getOrDefault(entry, 0L);
				long cost = costOf(entry).orElse(-1);
				entries.add(new BrowserEntry(entry, pretty(entry),
						wing.getKey(), xp, cost));
			}
		}

		return entries;
	}

	/** The wing names in display order, for the browser's filter cycle. */
	public static List<String> wingNames() {
		return wings == null ? List.of() : List.copyOf(wings.keySet());
	}

	/** Every known item display name - the autofill corpus for searches. */
	public static List<String> knownItemNames() {
		ensureListLoaded();
		return displayNames.isEmpty() ? List.of()
				: List.copyOf(new java.util.TreeSet<>(displayNames.values()));
	}

	/** Sync state for /skyaid dump: answers "why is the tooltip line missing". */
	public static void dumpInto(StringBuilder out) {
		out.append("\nMUSEUM SYNC:\n");
		out.append("  list loaded: ").append(wings != null
				? wings.values().stream().mapToInt(Set::size).sum() + " entries"
				: "NO").append('\n');
		out.append("  donated:     ").append(donated == null ? "not fetched yet"
						: donated.size() + " entries, "
								+ (System.currentTimeMillis() - donatedFetchedAt) / 1000
								+ "s old")
				.append('\n');
		out.append("  key set: ").append(HypixelApiClient.hasApiKey())
				.append(", rejected: ").append(HypixelApiClient.keyLooksRejected())
				.append(", fetch in flight: ").append(autoFetching.get()).append('\n');
		out.append("  rate budget left (of 300/5min): ")
				.append(HypixelApiClient.rateBudgetRemaining() < 0
						? "(no keyed request yet)"
						: HypixelApiClient.rateBudgetRemaining())
				.append('\n');

		out.append("  seen deposited locally (API cache lagging): ")
				.append(localDonations.isEmpty() ? "(none)" : localDonations)
				.append('\n');

		// The raw donated keys, exactly as the API spelled them - the way to
		// catch the API naming an entry differently than the bundled list
		// (observed live: Hypixel showed a set donated while the tooltip said
		// not, so one of the two spellings was wrong).
		Set<String> owned = donated;

		if (owned != null && !owned.isEmpty()) {
			List<String> keys = new ArrayList<>(owned);
			java.util.Collections.sort(keys);
			out.append("  donated keys (").append(keys.size()).append("):\n");

			for (int i = 0; i < Math.min(keys.size(), 60); i++) {
				out.append("    ").append(keys.get(i)).append('\n');
			}
		}
	}

	/** Buying cost of an entry: an item's price, or a set's pieces summed. */
	private static java.util.OptionalLong costOf(String entry) {
		List<String> pieces = setToPieces == null ? null : setToPieces.get(entry);

		if (pieces == null) {
			return PriceTooltips.peekBuyPriceOf(entry);
		}

		long total = 0;

		for (String piece : pieces) {
			java.util.OptionalLong price = PriceTooltips.peekBuyPriceOf(piece);

			if (price.isEmpty()) {
				return java.util.OptionalLong.empty();
			}

			total += price.getAsLong();
		}

		return java.util.OptionalLong.of(total);
	}

	/** Every entry key the member has donated, sets included. */
	private static Set<String> donatedEntries(JsonObject museum, String uuid) {
		Set<String> owned = new HashSet<>();
		JsonObject members = museum.getAsJsonObject("members");

		if (members == null || !members.has(uuid)) {
			return owned;
		}

		JsonObject member = members.getAsJsonObject(uuid);
		JsonObject items = member.getAsJsonObject("items");

		if (items != null) {
			owned.addAll(items.keySet());
		}

		// Special-wing donations arrive as raw item NBT; the ids live at
		// tag.ExtraAttributes.id inside each blob (API-side NBT is the old
		// nested shape, unlike the client's unwrapped custom data).
		if (member.has("special") && member.get("special").isJsonArray()) {
			for (JsonElement element : member.getAsJsonArray("special")) {
				specialItemIds(element.getAsJsonObject(), owned);
			}
		}

		return owned;
	}

	/** An NBT blob holds one small item; a megabyte is a generous roof. */
	private static final long MAX_ITEM_NBT_BYTES = 1_048_576;

	private static void specialItemIds(JsonObject entry, Set<String> into) {
		try {
			JsonObject blob = entry.getAsJsonObject("items");
			String base64 = blob != null && blob.has("data")
					? blob.get("data").getAsString() : null;

			if (base64 == null) {
				return;
			}

			var root = net.minecraft.nbt.NbtIo.readCompressed(
					new java.io.ByteArrayInputStream(
							java.util.Base64.getDecoder().decode(base64)),
					net.minecraft.nbt.NbtAccounter.create(MAX_ITEM_NBT_BYTES));
			var list = root.getListOrEmpty("i");

			for (int i = 0; i < list.size(); i++) {
				list.getCompoundOrEmpty(i)
						.getCompoundOrEmpty("tag")
						.getCompoundOrEmpty("ExtraAttributes")
						.getString("id")
						.ifPresent(into::add);
			}
		} catch (Exception e) {
			// One undecodable blob must not sink the whole sync.
		}
	}

	private static String selectedProfileId(Optional<JsonObject> profiles) {
		if (profiles.isEmpty() || !profiles.get().has("profiles")
				|| !profiles.get().get("profiles").isJsonArray()) {
			return null;
		}

		for (JsonElement element : profiles.get().getAsJsonArray("profiles")) {
			JsonObject profile = element.getAsJsonObject();

			if (profile.has("selected") && profile.get("selected").getAsBoolean()
					&& profile.has("profile_id")) {
				return profile.get("profile_id").getAsString();
			}
		}

		return null;
	}

	/** Loads the bundled donatable list once; safe to call repeatedly. */
	private static synchronized void ensureListLoaded() {
		if (wings != null) {
			return;
		}

		try (InputStreamReader reader = new InputStreamReader(
				MuseumTracker.class.getResourceAsStream("/assets/skyaid/museum.json"),
				StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			JsonObject items = root.getAsJsonObject("items");
			Map<String, Set<String>> loaded = new LinkedHashMap<>();

			for (String wing : items.keySet()) {
				Set<String> entries = new HashSet<>();

				for (JsonElement element : items.getAsJsonArray(wing)) {
					entries.add(element.getAsString());
				}

				loaded.put(wing, entries);
			}

			Map<String, String> pieces = new HashMap<>();
			Map<String, List<String>> setContents = new HashMap<>();
			JsonObject sets = root.getAsJsonObject("sets_to_items");

			if (sets != null) {
				for (String set : sets.keySet()) {
					List<String> members = new ArrayList<>();

					for (JsonElement piece : sets.getAsJsonArray(set)) {
						pieces.put(piece.getAsString(), set);
						members.add(piece.getAsString());
					}

					setContents.put(set, members);
				}
			}

			Map<String, Long> xp = new HashMap<>();
			JsonObject xpTable = root.getAsJsonObject("itemToXp");

			if (xpTable != null) {
				for (String entry : xpTable.keySet()) {
					xp.put(entry, xpTable.get(entry).getAsLong());
				}
			}

			Map<String, String> mapped = new HashMap<>();
			JsonObject mappedTable = root.getAsJsonObject("mapped_ids");

			if (mappedTable != null) {
				for (String alternate : mappedTable.keySet()) {
					mapped.put(alternate, mappedTable.get(alternate).getAsString());
				}
			}

			mappedIds = mapped;
			entryToXp = xp;
			setToPieces = setContents;
			pieceToSet = pieces;
			wings = loaded;
			loadDisplayNames();
		} catch (Exception e) {
			dev.skyaid.SkyAidClient.LOGGER.warn("Could not load the museum list", e);
		}
	}

	/**
	 * Hypixel's exact display names, harvested per entry - what the AH
	 * search actually matches ("Dreadlord Sword", not "Crypt Dreadlord
	 * Sword"). Missing entries keep the id-derived name.
	 */
	private static void loadDisplayNames() {
		try (InputStreamReader reader = new InputStreamReader(
				MuseumTracker.class.getResourceAsStream(
						"/assets/skyaid/museum-names.json"),
				StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			Map<String, String> names = new HashMap<>(root.size());

			for (String id : root.keySet()) {
				names.put(id, root.get(id).getAsString());
			}

			displayNames = names;
		} catch (Exception e) {
			dev.skyaid.SkyAidClient.LOGGER.warn("Could not load museum display names");
		}
	}

	private static String pretty(String id) {
		String exact = displayNames.get(id);

		if (exact != null) {
			return exact;
		}

		return prettyFromId(id);
	}

	private static String prettyFromId(String id) {
		StringBuilder out = new StringBuilder(id.length());

		for (String word : id.split("_")) {
			if (word.isEmpty()) {
				continue;
			}

			if (!out.isEmpty()) {
				out.append(' ');
			}

			out.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
		}

		return out.toString();
	}

	private static void say(Component message) {
		var client = Minecraft.getInstance();

		if (client.gui != null) {
			// A blank line either side, the same breathing room the help
			// and session blocks get.
			var chat = client.gui.hud.getChat();
			chat.addClientSystemMessage(Component.empty());
			chat.addClientSystemMessage(message);
			chat.addClientSystemMessage(Component.empty());
		}
	}
}
