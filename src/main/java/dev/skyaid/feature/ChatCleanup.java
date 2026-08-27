package dev.skyaid.feature;

import dev.skyaid.config.Config;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.parse.ChatRules;
import dev.skyaid.parse.Timestamps;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tidies the Hypixel chat: hides lobby join spam, store adverts and repeats,
 * timestamps lines, and marks party, guild and mentions of you.
 *
 * <p>Purely a display filter on the receiving client. Nothing is sent to the
 * server, and nothing that matters for fair play is hidden - the rules only
 * match Hypixel's own announcements and adverts.
 */
public final class ChatCleanup {
	/**
	 * How many recent lines to remember when checking for repeats.
	 *
	 * <p>Generous because the window is configurable up to five minutes: a busy
	 * lobby produces hundreds of lines in that time, and remembering only a handful
	 * would silently shorten a long window to "the last few messages".
	 */
	private static final int RECENT_HISTORY = 256;

	private static final Deque<Seen> recent = new ArrayDeque<>();

	private record Seen(String text, long timestamp) {
	}

	private ChatCleanup() {
	}

	public static void register() {
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			// The action bar is the Skyblock stat display - never filter it.
			if (overlay || !active()) {
				return true;
			}

			Config.ChatSettings settings = ConfigManager.get().chat;
			String text = message.getString();

			if (settings.hideLobbyJoinSpam
					&& ChatRules.classify(text) == ChatRules.Kind.LOBBY_JOIN) {
				return false;
			}

			if (settings.hidePromotions && ChatRules.isPromotion(text)) {
				return false;
			}

			if (settings.hideAbilityCooldown && ChatRules.isAbilityCooldown(text)) {
				return false;
			}

			if (settings.hideSackMessages && ChatRules.isSackMessage(text)) {
				return false;
			}

			return !(settings.hideDuplicateMessages && isRecentDuplicate(text));
		});

		ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
			if (overlay || !active()) {
				return message;
			}

			Config.ChatSettings settings = ConfigManager.get().chat;
			Component result = message;

			if (settings.highlightMentions && mentionsPlayer(message.getString())) {
				result = marked(result, ChatFormatting.GOLD);
			} else if (settings.highlightAuctions
					&& ChatRules.isAuctionAnnouncement(message.getString())) {
				result = marked(result, ChatFormatting.YELLOW);
			} else if (settings.highlightPartyAndGuild) {
				result = switch (ChatRules.classify(message.getString())) {
					case PARTY -> marked(result, ChatFormatting.AQUA);
					case GUILD -> marked(result, ChatFormatting.GREEN);
					default -> result;
				};
			}

			// Applied last so the time sits at the very start of the line, ahead of
			// any marker.
			if (settings.timestamps) {
				String clock = Timestamps.format(LocalTime.now(), settings.timestamps12Hour);
				result = Component.literal("[" + clock + "] ")
						.withStyle(ChatFormatting.DARK_GRAY)
						.append(result);
			}

			return result;
		});

		// Fires only for messages that survived the filter above, which is exactly
		// when a mention is worth a sound - a hidden line should not ping.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !active()) {
				return;
			}

			Config.ChatSettings settings = ConfigManager.get().chat;
			String text = message.getString();

			if (settings.mentionSound && mentionsPlayer(text)) {
				ping(1.0f);
			} else if (settings.highlightAuctions && ChatRules.isAuctionAnnouncement(text)) {
				// Higher pitch than a mention, so the ear can tell them apart.
				ping(1.5f);
			}
		});
	}

	private static void ping(float pitch) {
		Minecraft.getInstance().getSoundManager().playDelayed(
				SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, pitch), 0);
	}

	private static boolean active() {
		return ConfigManager.get().enabled && HypixelDetector.isOnHypixel();
	}

	/** Your name, or any of the custom words from /skyaid highlight. */
	private static boolean mentionsPlayer(String message) {
		var user = Minecraft.getInstance().getUser();

		if (user != null && ChatRules.mentions(message, user.getName())) {
			return true;
		}

		for (String word : ConfigManager.get().chat.highlightWords) {
			if (ChatRules.mentions(message, word)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Prefixes a marker instead of restyling the line. Hypixel's own colouring
	 * already carries meaning - overriding it would lose the rank colours - so the
	 * marker adds a scannable edge without touching the original text.
	 */
	private static Component marked(Component message, ChatFormatting color) {
		return Component.literal("| ").withStyle(color).append(message);
	}

	private static boolean isRecentDuplicate(String text) {
		long now = System.currentTimeMillis();

		long window = ConfigManager.get().chat.duplicateWindowSeconds * 1000L;

		recent.removeIf(seen -> now - seen.timestamp() > window);

		for (Seen seen : recent) {
			if (seen.text().equals(text)) {
				return true;
			}
		}

		while (recent.size() >= RECENT_HISTORY) {
			recent.removeFirst();
		}

		recent.addLast(new Seen(text, now));
		return false;
	}
}
