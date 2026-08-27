package dev.skyaid.core;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.Optional;

/**
 * Session log of Hypixel's own drop announcements - "RARE DROP!" (and its
 * VERY/CRAZY variants), "PET DROP!", "INSANE DROP!" - wordings from
 * ecosystem knowledge until a capture confirms them. Keeps the count and
 * the latest name for the drops HUD element; clears with the game.
 */
public final class DropTracker {
	private static int count;
	private static String lastName = "";
	private static long lastAt;

	private DropTracker() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return;
			}

			String text = message.getString();
			int marker = text.indexOf("RARE DROP!");

			if (marker < 0) {
				marker = text.indexOf("PET DROP!");
			}

			if (marker < 0) {
				marker = text.indexOf("INSANE DROP!");
			}

			if (marker < 0) {
				return;
			}

			count++;
			LifetimeStats.countRareDrop();
			lastAt = System.currentTimeMillis();
			String name = text.substring(text.indexOf('!', marker) + 1).trim();

			// The magic-find suffix "(+218% Magic Find!)" is noise here.
			int paren = name.indexOf(" (");

			if (paren > 0) {
				name = name.substring(0, paren);
			}

			lastName = name.isEmpty() ? "?" : name;
		});
	}

	public static int count() {
		return count;
	}

	/** "Sinful Dice (4m ago)". */
	public static Optional<String> last() {
		if (count == 0) {
			return Optional.empty();
		}

		return Optional.of(lastName + " (" + ago(lastAt) + ")");
	}

	/** "just now", "4m ago", "2h ago" - shared with the fishing element. */
	public static String ago(long at) {
		long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);

		if (minutes < 1) {
			return "just now";
		}

		if (minutes < 60) {
			return minutes + "m ago";
		}

		return (minutes / 60) + "h ago";
	}
}
