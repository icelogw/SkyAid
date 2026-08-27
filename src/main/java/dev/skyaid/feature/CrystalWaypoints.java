package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.parse.FormatCodes;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.BlockPos;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crystal Hollows structure callouts: a chat line that names a structure
 * and carries coordinates - a party member sharing "Temple 502 105 489" -
 * becomes a labelled waypoint beam. Reading chat only; nothing is sent.
 *
 * <p>Waypoints live long enough to cross the map and share the dungeon
 * chat-waypoint toggle, since it is the same feature in a different cave.
 */
public final class CrystalWaypoints {
	private static final long LIFETIME_MILLIS = 15 * 60_000L;

	/** Keyword (lower-case, contains-match) -> the waypoint's label. */
	private static final Map<String, String> STRUCTURES = Map.of(
			"jungle temple", "Jungle Temple",
			"temple", "Jungle Temple",
			"divan", "Mines of Divan",
			"goblin queen", "Goblin Queen's Den",
			"queen", "Goblin Queen's Den",
			"precursor", "Lost Precursor City",
			"city", "Lost Precursor City",
			"khazad", "Khazad-dum",
			"bal", "Khazad-dum",
			"grotto", "Fairy Grotto");

	/** Three whole numbers with anything reasonable between them. */
	private static final Pattern COORDS = Pattern.compile(
			".*?(-?\\d{1,4})[,;:xyz\\s]+(-?\\d{1,3})[,;:xyz\\s]+(-?\\d{1,4}).*");

	private CrystalWaypoints() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !ConfigManager.get().enabled
					|| !ConfigManager.get().chat.chatWaypoints
					|| !HypixelDetector.isOnHypixel()
					|| !CrystalHollows.inCrystalHollows()) {
				return;
			}

			String text = FormatCodes.strip(message.getString()).trim();
			String lower = text.toLowerCase(Locale.ROOT);
			String label = null;

			for (Map.Entry<String, String> structure : STRUCTURES.entrySet()) {
				if (lower.contains(structure.getKey())) {
					label = structure.getValue();
					break;
				}
			}

			if (label == null) {
				return;
			}

			Matcher coords = COORDS.matcher(text);

			if (!coords.matches()) {
				return;
			}

			int x = Integer.parseInt(coords.group(1));
			int y = Integer.parseInt(coords.group(2));
			int z = Integer.parseInt(coords.group(3));

			// The Hollows are a 1024x1024 box; garbage numbers stay chat.
			if (x < 0 || x > 1024 || y < 0 || y > 256 || z < 0 || z > 1024) {
				return;
			}

			Waypoints.place(label, new BlockPos(x, y, z), LIFETIME_MILLIS);
		});
	}
}
