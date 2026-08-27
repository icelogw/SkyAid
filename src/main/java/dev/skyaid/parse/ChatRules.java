package dev.skyaid.parse;

import java.util.Locale;

/**
 * Classifies a Hypixel chat line.
 *
 * <p>Plain string tests rather than regexes: the distinguishing features here are
 * fixed prefixes and suffixes, so matching on those is both clearer and harder to
 * break than a pattern trying to account for every rank and colour code.
 *
 * <p>This only ever decides how a message is <em>displayed</em> on the client.
 * Nothing here suppresses anything the player needs to see for fair play, and
 * nothing is sent back to the server.
 */
public final class ChatRules {
	private ChatRules() {
	}

	/** What kind of message a line is, as far as the chat feature cares. */
	public enum Kind {
		LOBBY_JOIN,
		PARTY,
		GUILD,
		DIRECT_MESSAGE,
		OTHER
	}

	public static Kind classify(String rawMessage) {
		String line = FormatCodes.strip(rawMessage);

		if (line.isEmpty()) {
			return Kind.OTHER;
		}

		// Hypixel wraps the louder join announcements in chevrons.
		String trimmed = stripChevrons(line);

		if (isLobbyJoin(trimmed)) {
			return Kind.LOBBY_JOIN;
		}

		if (startsWithChannel(trimmed, "Party >")) {
			return Kind.PARTY;
		}

		if (startsWithChannel(trimmed, "Guild >")) {
			return Kind.GUILD;
		}

		if (startsWithChannel(trimmed, "From ") || startsWithChannel(trimmed, "To ")) {
			return Kind.DIRECT_MESSAGE;
		}

		return Kind.OTHER;
	}

	/**
	 * Hypixel's own advertising. Matched on the store address rather than on words
	 * like "sale", which would swallow players talking about the auction house.
	 */
	/**
	 * The item-ability rejection notice, printed every time an ability is used
	 * before its cooldown is up - which during a fight can be several times a
	 * second. Hiding it loses nothing: the ability simply not firing already
	 * tells the player the same thing.
	 */
	public static boolean isAbilityCooldown(String rawMessage) {
		return FormatCodes.strip(rawMessage).trim()
				.startsWith("This ability is on cooldown");
	}

	/**
	 * Hypixel's auction announcements - your auction selling, expiring, or you
	 * being outbid - all arrive prefixed "[Auction]". Worth marking: they are
	 * easy to miss in a busy lobby and usually mean coins waiting to be claimed.
	 */
	public static boolean isAuctionAnnouncement(String rawMessage) {
		return FormatCodes.strip(rawMessage).trim().startsWith("[Auction]");
	}

	/**
	 * The sack collection notice - "[Sacks] +240 items..." - printed every few
	 * seconds while farming or mining. Hiding it loses nothing: the items are
	 * visible in the sack itself.
	 */
	public static boolean isSackMessage(String rawMessage) {
		return FormatCodes.strip(rawMessage).trim().startsWith("[Sacks]");
	}

	public static boolean isPromotion(String rawMessage) {
		String line = FormatCodes.strip(rawMessage).toLowerCase(Locale.ROOT);

		return line.contains("store.hypixel.net")
				|| line.contains("hypixel.net/store")
				|| line.equals("www.hypixel.net");
	}

	/**
	 * Whether a message mentions the given player by name.
	 *
	 * <p>Only the body counts, not the sender. Every message you send starts with
	 * your own name, so matching the whole line would flag everything you type as a
	 * mention of yourself.
	 */
	public static boolean mentions(String rawMessage, String playerName) {
		if (playerName == null || playerName.isBlank()) {
			return false;
		}

		String line = FormatCodes.strip(rawMessage);
		int colon = line.indexOf(": ");
		String body = colon >= 0 ? line.substring(colon + 2) : line;

		return body.toLowerCase(Locale.ROOT).contains(playerName.toLowerCase(Locale.ROOT));
	}

	/**
	 * Join lines end with "joined the lobby!". Matching the suffix rather than the
	 * whole line avoids having to model every rank prefix a player might have.
	 */
	private static boolean isLobbyJoin(String line) {
		return line.toLowerCase(Locale.ROOT).endsWith("joined the lobby!");
	}

	private static boolean startsWithChannel(String line, String prefix) {
		return line.regionMatches(true, 0, prefix, 0, prefix.length());
	}

	private static String stripChevrons(String line) {
		String out = line;

		if (out.startsWith(">>>")) {
			out = out.substring(3);
		}

		if (out.endsWith("<<<")) {
			out = out.substring(0, out.length() - 3);
		}

		return out.trim();
	}
}
