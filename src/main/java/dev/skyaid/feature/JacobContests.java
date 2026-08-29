package dev.skyaid.feature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.core.SkyblockTracker;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Jacob's contest schedule: which crops are up next and when, as a HUD line
 * and an optional chat ping a few minutes before a watched crop's contest.
 *
 * <p>The schedule comes from the community Elite farmers API (elitebot.dev),
 * keyless and read-only: contest START times mapping to their three crops.
 * Contests run twenty minutes. If the fetch fails the line simply stays
 * absent - nothing else depends on it.
 */
public final class JacobContests {
	private static final String URL = "https://api.elitebot.dev/contests/at/now";
	private static final long REFRESH_MILLIS = 3 * 60 * 60_000L;
	private static final long CONTEST_MILLIS = 20 * 60_000L;

	/** How long before the start the watched-crop ping fires. */
	private static final long ALERT_AHEAD_MILLIS = 5 * 60_000L;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	/** Contest start (millis) -> its three crops; sorted for "next". */
	private static volatile TreeMap<Long, List<String>> contests = new TreeMap<>();
	private static volatile long fetchedAt;
	private static final AtomicBoolean fetching = new AtomicBoolean();

	/** Start times already pinged, so one contest alerts once. */
	private static final java.util.Set<Long> alerted =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	private JacobContests() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ConfigManager.get().enabled || client.player == null
					|| !HypixelDetector.isOnHypixel()
					|| !SkyblockTracker.state().inSkyblock()) {
				return;
			}

			ensureFresh();

			if (!ConfigManager.get().jacobAlerts) {
				return;
			}

			long now = System.currentTimeMillis();

			for (Map.Entry<Long, List<String>> entry : upcoming(now, 1)) {
				long until = entry.getKey() - now;

				if (until > 0 && until <= ALERT_AHEAD_MILLIS
						&& watchedCropIn(entry.getValue())
						&& alerted.add(entry.getKey())) {
					say(Component.literal("Jacob's contest in "
									+ (until / 60_000 + 1) + "m: ")
							.withStyle(ChatFormatting.GOLD)
							.append(Component.literal(String.join(", ", entry.getValue()))
									.withStyle(ChatFormatting.GREEN)));
				}
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("jacob")
								.executes(context -> {
									reportUpcoming();
									return 1;
								})
								.then(ClientCommands.literal("watch")
										.then(ClientCommands.argument("crop",
														com.mojang.brigadier.arguments.StringArgumentType
																.greedyString())
												.executes(context -> {
													toggleWatch(com.mojang.brigadier.arguments
															.StringArgumentType.getString(
																	context, "crop"));
													return 1;
												}))))));
	}

	/** "Jacob: Cactus, Wheat in 14m" or the running contest's time left. */
	public static Optional<Component> hudLine() {
		long now = System.currentTimeMillis();
		Map.Entry<Long, List<String>> active = activeContest(now);

		if (active != null) {
			long left = active.getKey() + CONTEST_MILLIS - now;
			return Optional.of(Component.literal("Jacob NOW: ")
					.withStyle(ChatFormatting.GOLD)
					.append(Component.literal(String.join(", ", active.getValue()))
							.withStyle(ChatFormatting.GREEN))
					.append(Component.literal("  " + left / 60_000 + "m left")
							.withStyle(ChatFormatting.GRAY)));
		}

		var next = upcoming(now, 1);

		if (next.isEmpty()) {
			return Optional.empty();
		}

		var entry = next.get(0);
		return Optional.of(Component.literal("Jacob: ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(String.join(", ", entry.getValue()))
						.withStyle(ChatFormatting.GREEN))
				.append(Component.literal("  in " + minutes(entry.getKey() - now))
						.withStyle(ChatFormatting.GRAY)));
	}

	private static String minutes(long millis) {
		long total = Math.max(0, millis) / 60_000;
		return total >= 60 ? total / 60 + "h " + total % 60 + "m" : total + "m";
	}

	private static Map.Entry<Long, List<String>> activeContest(long now) {
		var entry = contests.floorEntry(now);
		return entry != null && now < entry.getKey() + CONTEST_MILLIS ? entry : null;
	}

	private static List<Map.Entry<Long, List<String>>> upcoming(long now, int count) {
		List<Map.Entry<Long, List<String>>> out = new ArrayList<>(count);

		for (var entry : contests.tailMap(now, false).entrySet()) {
			out.add(entry);

			if (out.size() >= count) {
				break;
			}
		}

		return out;
	}

	private static boolean watchedCropIn(List<String> crops) {
		List<String> watched = ConfigManager.get().jacobWatchedCrops;

		if (watched.isEmpty()) {
			return false; // No watch list, no pings - the HUD line informs.
		}

		for (String crop : crops) {
			for (String want : watched) {
				if (crop.equalsIgnoreCase(want)) {
					return true;
				}
			}
		}

		return false;
	}

	private static void toggleWatch(String crop) {
		String cleaned = crop.trim();
		var watched = ConfigManager.get().jacobWatchedCrops;
		boolean removed = watched.removeIf(entry -> entry.equalsIgnoreCase(cleaned));

		if (!removed) {
			watched.add(cleaned);
		}

		ConfigManager.save();
		say(Component.literal(removed
						? "No longer watching " + cleaned + "."
						: "Watching " + cleaned + " - you get a ping 5 minutes"
								+ " before its contests.")
				.withStyle(ChatFormatting.GREEN)
				.append(Component.literal(watched.isEmpty()
								? "  (watch list empty - no pings)"
								: "  Watched: " + String.join(", ", watched))
						.withStyle(ChatFormatting.GRAY)));
	}

	private static void reportUpcoming() {
		ensureFresh();
		long now = System.currentTimeMillis();
		var next = upcoming(now, 5);

		if (next.isEmpty()) {
			say(Component.literal("No contest schedule yet - it loads from"
							+ " elitebot.dev in the background; try again shortly.")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		var message = Component.literal("Next Jacob's contests:")
				.withStyle(ChatFormatting.AQUA);

		for (var entry : next) {
			message = message.copy()
					.append(Component.literal("\n  " + String.join(", ", entry.getValue()))
							.withStyle(ChatFormatting.GREEN))
					.append(Component.literal("  in " + minutes(entry.getKey() - now))
							.withStyle(ChatFormatting.GRAY));
		}

		var watched = ConfigManager.get().jacobWatchedCrops;
		message = message.copy().append(Component.literal(watched.isEmpty()
						? "\n  Watch a crop with /skyaid jacob watch <crop>"
						: "\n  Watched: " + String.join(", ", watched))
				.withStyle(ChatFormatting.DARK_GRAY));

		say(message);
	}

	private static void ensureFresh() {
		long now = System.currentTimeMillis();
		boolean exhausted = contests.isEmpty() || contests.lastKey() < now;

		if ((now - fetchedAt < REFRESH_MILLIS && !exhausted)
				|| !fetching.compareAndSet(false, true)) {
			return;
		}

		HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
				.timeout(Duration.ofSeconds(15))
				.header("Accept", "application/json")
				.header("User-Agent", "SkyAid (Fabric mod)")
				.GET()
				.build();

		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.handle((response, error) -> {
					try {
						if (error != null || response.statusCode() != 200) {
							dev.skyaid.core.EventLog.event("jacob", "schedule fetch failed: " + (error != null ? error.getClass().getSimpleName() : "HTTP " + response.statusCode()));
							fetchedAt = now - REFRESH_MILLIS + 5 * 60_000; // retry in 5m
							return null;
						}

						JsonObject body = JsonParser.parseString(
								response.body()).getAsJsonObject();
						JsonObject schedule = body.getAsJsonObject("contests");

						if (schedule == null) {
							return null;
						}

						TreeMap<Long, List<String>> parsed = new TreeMap<>();

						for (String key : schedule.keySet()) {
							if (!schedule.get(key).isJsonArray()) {
								continue;
							}

							List<String> crops = new ArrayList<>(3);

							for (var crop : schedule.getAsJsonArray(key)) {
								crops.add(crop.getAsString());
							}

							parsed.put(Long.parseLong(key) * 1000L, crops);
						}

						dev.skyaid.core.EventLog.event("jacob", "schedule loaded: " + parsed.size() + " contests");
						contests = parsed;
						fetchedAt = now;
					} catch (Exception e) {
						dev.skyaid.SkyAidClient.LOGGER.warn(
								"Jacob schedule parse failed", e);
					} finally {
						fetching.set(false);
					}

					return null;
				});
	}

	public static void dumpInto(StringBuilder out) {
		out.append("\nJACOB CONTESTS:\n");
		out.append("  schedule entries: ").append(contests.size())
				.append(fetchedAt == 0 ? " (never fetched)"
						: ", fetched " + (System.currentTimeMillis() - fetchedAt) / 1000
								+ "s ago")
				.append('\n');
		out.append("  watched: ").append(String.join(", ",
				ConfigManager.get().jacobWatchedCrops)).append('\n');
	}

	private static void say(Component message) {
		var client = Minecraft.getInstance();

		if (client.gui != null) {
			var chat = client.gui.hud.getChat();
			chat.addClientSystemMessage(Component.empty());
			chat.addClientSystemMessage(message);
			chat.addClientSystemMessage(Component.empty());
		}
	}
}
