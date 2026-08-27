package dev.skyaid.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Replaces Hypixel's sign-based market search outright: when the AH or bazaar
 * search sign opens, {@link MarketSearchScreen} - a real text box with
 * history and item-name autofill - takes its place immediately. Submitting
 * writes the query into the hidden sign and closes it, which is exactly what
 * the sign's own Done button does; cancelling closes it untouched, so
 * Hypixel's flow is never left hanging.
 *
 * <p>The sign's typed lines are reached through the widened
 * {@link AbstractSignEditScreen#messages} array. History persists in the game
 * folder; completions come from the bundled item display names.
 */
public final class SignSearchAssist {
	private static final int MAX_HISTORY = 20;
	private static final int MAX_ROWS = 8;

	/** How recently a market screen must have been open to claim the sign. */
	private static final long MARKET_WINDOW_MILLIS = 15_000;

	private static final Path HISTORY_FILE =
			net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
					.resolve("skyaid-search-history.json");

	private static final List<String> history = new ArrayList<>();
	private static boolean historyLoaded;

	private static String lastMarketTitle = "";
	private static long lastMarketAt;

	private SignSearchAssist() {
	}

	public static void register() {
		// The freshness window must run from when a market screen was last
		// OPEN, not last opened: browsing AH results longer than the window
		// and then clicking Search handed the sign to vanilla.
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
				.register(client -> {
					if (client.gui == null
							|| !(client.gui.screen() instanceof AbstractContainerScreen<?> open)) {
						return;
					}

					String title = open.getTitle().getString();

					if (title.contains("Auction") || title.contains("Bazaar")
							|| title.contains("Bank")) {
						lastMarketTitle = title;
						lastMarketAt = System.currentTimeMillis();
					}
				});

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!ConfigManager.get().enabled || !HypixelDetector.isOnHypixel()) {
				return;
			}

			// Remember the last market screen; the search sign opens right
			// after it and inherits its context. The Bank counts too: its
			// deposit and withdraw amounts are the same sign flow
			// (screenshot-verified hints "Enter the amount to withdraw").
			if (screen instanceof AbstractContainerScreen<?>) {
				String title = screen.getTitle().getString();

				if (title.contains("Auction") || title.contains("Bazaar")
						|| title.contains("Bank")) {
					lastMarketTitle = title;
					lastMarketAt = System.currentTimeMillis();
				}

				return;
			}

			if (!(screen instanceof AbstractSignEditScreen sign)) {
				return;
			}

			if (System.currentTimeMillis() - lastMarketAt > MARKET_WINDOW_MILLIS) {
				return;
			}

			// Hypixel uses the SAME sign editor for search, order price and
			// order amount - the prefilled hint lines are what tell them
			// apart. An unrecognised sign stays vanilla: the wrong custom
			// menu is worse than no custom menu (a real failure: the bazaar
			// price sign opened the SEARCH screen).
			String hint = (sign.messages[1] + " " + sign.messages[2] + " "
					+ sign.messages[3]).toLowerCase(Locale.ROOT);
			lastSignHints = new String[]{
					sign.messages[1], sign.messages[2], sign.messages[3]};

			boolean search = hint.contains("search") || hint.contains("query");
			boolean number = hint.contains("price") || hint.contains("bid")
					|| hint.contains("amount") || hint.contains("many")
					|| hint.contains("much") || hint.contains("coins");

			if (!search && !number) {
				lastSignVerdict = "vanilla (unrecognised hints)";
				return;
			}

			loadHistory();

			// The sign never gets a frame: our screen replaces it. The swap
			// fires the sign screen's removed(), whose automatic sign update
			// (still empty) must be suppressed - Hypixel reads an empty
			// answer as "cancel" and reopens the market menu right over our
			// screen. The mixin claims that one removal.
			hijacked = sign;

			if (search) {
				lastSignVerdict = "search screen";
				boolean bazaar = lastMarketTitle.contains("Bazaar");
				client.execute(() -> client.setScreenAndShow(
						new MarketSearchScreen(sign, bazaar)));
			} else {
				lastSignVerdict = "number screen";
				String[] hints = lastSignHints;
				client.execute(() -> client.setScreenAndShow(
						new MarketNumberScreen(sign, hints)));
			}
		});
	}

	/** What the last market sign's hints were and how they classified. */
	private static volatile String[] lastSignHints;
	private static volatile String lastSignVerdict = "(none seen)";

	/** For /skyaid dump: the capture that extends the hint classifier. */
	public static void dumpInto(StringBuilder out) {
		out.append("\nMARKET SIGN:\n");
		out.append("  verdict: ").append(lastSignVerdict).append('\n');

		String[] hints = lastSignHints;

		if (hints != null) {
			for (String hint : hints) {
				out.append("  hint: \"").append(hint).append("\"\n");
			}
		}
	}

	/** The sign screen we replaced; its removal must not send the sign. */
	private static AbstractSignEditScreen hijacked;

	/** One-shot, called by the mixin: is this removal the hijack swap? */
	public static boolean claimRemoval(AbstractSignEditScreen screen) {
		if (screen == hijacked) {
			hijacked = null;
			return true;
		}

		return false;
	}

	/** History first, then item-name completions - at most eight rows. */
	static List<String> suggestionsFor(String typed, boolean bazaar) {
		String query = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
		List<String> rows = new ArrayList<>();

		for (String entry : history) {
			if (rows.size() >= MAX_ROWS) {
				break;
			}

			if (query.isEmpty() || entry.toLowerCase(Locale.ROOT).contains(query)) {
				rows.add(entry);
			}
		}

		if (!query.isEmpty()) {
			for (String name : bazaar ? bazaarNames() : MuseumTracker.knownItemNames()) {
				if (rows.size() >= MAX_ROWS) {
					break;
				}

				if (name.toLowerCase(Locale.ROOT).contains(query)
						&& !containsIgnoreCase(rows, name)) {
					rows.add(name);
				}
			}
		}

		return rows;
	}

	/** Bazaar product names for BZ-search completions, rebuilt off-thread. */
	private static volatile List<String> bazaarNames = List.of();
	private static volatile long bazaarNamesAskedAt;

	/**
	 * The bazaar's own product names - what searching in the BZ should
	 * complete against; the museum names miss bazaar-only items entirely.
	 * Serves whatever is built right now and refreshes in the background, so
	 * typing never waits on the network. The endpoint is keyless.
	 */
	static List<String> bazaarNames() {
		if (System.currentTimeMillis() - bazaarNamesAskedAt > 60_000) {
			bazaarNamesAskedAt = System.currentTimeMillis();

			dev.skyaid.api.HypixelApiClient.get("/skyblock/bazaar", 60_000, false)
					.thenAccept(body -> body.ifPresent(json -> {
						var products = json.getAsJsonObject("products");

						if (products == null) {
							return;
						}

						List<String> names = new ArrayList<>(products.size());

						for (String id : products.keySet()) {
							names.add(displayName(id));
						}

						names.sort(String.CASE_INSENSITIVE_ORDER);
						bazaarNames = names;
					}));
		}

		return bazaarNames;
	}

	/** Ids rendered the way Hypixel's own search knows them. */
	private static String displayName(String id) {
		return dev.skyaid.parse.Bazaar.displayName(id);
	}

	static boolean isHistory(String value) {
		return containsIgnoreCase(history, value);
	}

	/**
	 * Runs the search. The query goes out as the /ahs (or /bz) command - the
	 * same field-proven path as the F1/F2 keys - NOT through the sign's text:
	 * writing the query into the sign update reached Hypixel's backend as
	 * garbage ("Filtered: !?"), while the commands search correctly. The sign
	 * session is still answered first (untouched lines, vanilla's ESC), so
	 * Hypixel's flow closes cleanly, and the command that follows overwrites
	 * the filter with the real query.
	 */
	static void submitSearch(AbstractSignEditScreen sign, String query, boolean bazaar) {
		// The same cleaning the F1/F2 keys rely on, and for the same reason:
		// item display names carry unicode glyphs, and Hypixel KICKS for them
		// in chat ("Illegal characters in chat", log-verified). Everything
		// sent must be plain letters/digits/space/'/-.
		String clean = dev.skyaid.parse.ItemNames.cleanForSearch(query);

		if (clean.isEmpty()) {
			cancelSign(sign);
			return;
		}

		remember(clean);

		// Back THROUGH THE SIGN, not a command: /ahs and /bz need a Booster
		// Cookie, and a player without one could not search at all.
		// The sign is what the player is already standing at, cookie or not.
		// The earlier "Filtered: !?" garbage from this route predates name
		// cleaning - the same glyphs later kicked the command route - so the
		// query goes in cleaned, trimmed to the sign's own line width.
		var font = Minecraft.getInstance().font;
		String text = clean;

		while (!text.isEmpty() && font.width(text) > SIGN_LINE_WIDTH) {
			text = text.substring(0, text.length() - 1);
		}

		sign.messages[0] = text;
		sendSignUpdate(sign);
	}

	/** Vanilla's sign line width budget, so the query renders and sends whole. */
	private static final int SIGN_LINE_WIDTH = 90;

	/** Answers the hidden sign with its untouched lines - vanilla's ESC. */
	static void cancelSign(AbstractSignEditScreen sign) {
		sendSignUpdate(sign);
	}

	/**
	 * A price or amount from the number screen goes THROUGH the sign - there
	 * is no command equivalent for these. The value is digits and k/m/b
	 * shorthand only (the screen's filter guarantees it), so nothing the
	 * chat validator objects to can be in it.
	 */
	static void submitNumber(AbstractSignEditScreen sign, String text) {
		sign.messages[0] = text;
		sendSignUpdate(sign);
	}

	/**
	 * The exact packet vanilla's removed() would have sent, built from the
	 * widened sign fields. Sent by hand because the automatic one was
	 * suppressed at the hijack - the sign screen is long gone by the time
	 * the user submits, so nothing else will answer Hypixel.
	 */
	private static void sendSignUpdate(AbstractSignEditScreen sign) {
		var connection = Minecraft.getInstance().getConnection();

		if (connection != null) {
			connection.send(new net.minecraft.network.protocol.game.ServerboundSignUpdatePacket(
					sign.sign.getBlockPos(), sign.isFrontText,
					sign.messages[0], sign.messages[1], sign.messages[2], sign.messages[3]));
		}
	}

	private static boolean containsIgnoreCase(List<String> list, String value) {
		for (String entry : list) {
			if (entry.equalsIgnoreCase(value)) {
				return true;
			}
		}

		return false;
	}

	private static void remember(String query) {
		history.removeIf(entry -> entry.equalsIgnoreCase(query));
		history.add(0, query);

		while (history.size() > MAX_HISTORY) {
			history.remove(history.size() - 1);
		}

		try {
			JsonArray out = new JsonArray();
			history.forEach(out::add);
			Files.writeString(HISTORY_FILE, out.toString());
		} catch (Exception e) {
			dev.skyaid.SkyAidClient.LOGGER.warn("Could not save search history");
		}
	}

	private static void loadHistory() {
		if (historyLoaded) {
			return;
		}

		historyLoaded = true;

		try {
			if (!Files.exists(HISTORY_FILE)) {
				return;
			}

			// Trimmed and deduplicated on the way in: entries saved by older
			// builds carry stray whitespace, which slipped past the
			// duplicate check against completions and doubled rows.
			for (JsonElement entry : JsonParser.parseString(
					Files.readString(HISTORY_FILE)).getAsJsonArray()) {
				String value = entry.getAsString().trim();

				if (!value.isEmpty() && history.size() < MAX_HISTORY
						&& !containsIgnoreCase(history, value)) {
					history.add(value);
				}
			}
		} catch (Exception e) {
			dev.skyaid.SkyAidClient.LOGGER.warn("Could not load search history");
		}
	}
}
