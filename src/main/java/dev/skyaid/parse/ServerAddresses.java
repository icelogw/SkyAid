package dev.skyaid.parse;

import java.util.Locale;

/**
 * Server address matching, kept free of Minecraft types so it can be unit tested.
 *
 * <p>{@code dev.skyaid.core.HypixelDetector} delegates here. This check gates every
 * feature in the mod, so it is worth testing directly rather than only in-game.
 */
public final class ServerAddresses {
	private static final String HYPIXEL_ROOT = "hypixel.net";

	private ServerAddresses() {
	}

	/**
	 * True only for the Hypixel root domain and its subdomains.
	 *
	 * <p>Deliberately strict. A {@code contains("hypixel.net")} check would also
	 * accept {@code hypixel.net.evil.example}, which is not Hypixel - and since this
	 * predicate decides whether the mod sends the player's API key anywhere, a
	 * false positive matters.
	 */
	public static boolean isHypixel(String address) {
		if (address == null || address.isBlank()) {
			return false;
		}

		String host = address.toLowerCase(Locale.ROOT).trim();

		// Strip a bracketed IPv6 literal or a trailing :port.
		int colon = host.lastIndexOf(':');

		if (colon >= 0 && host.indexOf(']') < colon) {
			host = host.substring(0, colon);
		}

		// Tolerate a fully qualified name written with a trailing dot.
		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}

		return host.equals(HYPIXEL_ROOT) || host.endsWith("." + HYPIXEL_ROOT);
	}
}
