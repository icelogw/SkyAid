package dev.skyaid.parse;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * Recognises Hypixel's bank deposit and withdrawal messages, so the session
 * tracker can cancel them out - moving coins to or from the bank changes the
 * purse without anything being earned or spent, and would otherwise poison
 * Coins gained and Coins/h.
 *
 * <p>Plain prefix tests rather than a regex, matching the rest of this package.
 */
public final class BankTransfers {
	/**
	 * The REAL wordings, log-captured 2026-08-24: "Deposited 8.3M coins!
	 * There's now 39.5M coins in the account!" and "Withdrew 1k coins!
	 * There's now 39.5M coins left in the account!". The "You have..."
	 * forms were the ecosystem's remembered wording; they never matched a
	 * live transfer (an 8.3M swing hit the all-time ledger uncorrected)
	 * and are kept only as a harmless fallback.
	 */
	private static final String WITHDREW = "Withdrew ";
	private static final String DEPOSITED_NOW = "Deposited ";
	private static final String WITHDRAWN = "You have withdrawn ";
	private static final String DEPOSITED = "You have deposited ";

	private BankTransfers() {
	}

	/**
	 * How many coins this message moved <em>into</em> the purse: positive for a
	 * withdrawal, negative for a deposit, empty for any other message.
	 *
	 * <p>Hypixel abbreviates the amount the way the player typed it - "500k",
	 * "1.5M", "1,000,000" all appear - so all three forms are read.
	 */
	public static OptionalLong intoPurse(String rawMessage) {
		String line = FormatCodes.strip(rawMessage).trim();

		long sign;
		String rest;

		if (line.startsWith(WITHDREW)) {
			sign = 1;
			rest = line.substring(WITHDREW.length());
		} else if (line.startsWith(DEPOSITED_NOW)) {
			sign = -1;
			rest = line.substring(DEPOSITED_NOW.length());
		} else if (line.startsWith(WITHDRAWN)) {
			sign = 1;
			rest = line.substring(WITHDRAWN.length());
		} else if (line.startsWith(DEPOSITED)) {
			sign = -1;
			rest = line.substring(DEPOSITED.length());
		} else {
			return OptionalLong.empty();
		}

		// "...withdrawn 500k coins! You now have..." - the amount ends at " coin",
		// which also covers the singular "1 coin!".
		int end = rest.indexOf(" coin");

		if (end <= 0) {
			return OptionalLong.empty();
		}

		OptionalLong amount = parseAmount(rest.substring(0, end));

		if (amount.isEmpty()) {
			return OptionalLong.empty();
		}

		return OptionalLong.of(sign * amount.getAsLong());
	}

	/** "1,000,000", "500k", "1.5M", "2B" - commas dropped, suffix scaled. */
	public static OptionalLong parseAmount(String text) {
		String cleaned = text.replace(",", "").toLowerCase(Locale.ROOT);

		if (cleaned.isEmpty()) {
			return OptionalLong.empty();
		}

		long multiplier = 1;
		char last = cleaned.charAt(cleaned.length() - 1);

		switch (last) {
			case 'k' -> multiplier = 1_000L;
			case 'm' -> multiplier = 1_000_000L;
			case 'b' -> multiplier = 1_000_000_000L;
			default -> {
			}
		}

		if (multiplier > 1) {
			cleaned = cleaned.substring(0, cleaned.length() - 1);
		}

		if (cleaned.isEmpty() || !isPlainNumber(cleaned)) {
			return OptionalLong.empty();
		}

		try {
			return OptionalLong.of(Math.round(Double.parseDouble(cleaned) * multiplier));
		} catch (NumberFormatException e) {
			return OptionalLong.empty();
		}
	}

	/** Digits with at most one decimal point - nothing else gets near parseDouble. */
	private static boolean isPlainNumber(String text) {
		boolean dotSeen = false;

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (c == '.') {
				if (dotSeen) {
					return false;
				}

				dotSeen = true;
				continue;
			}

			if (c < '0' || c > '9') {
				return false;
			}
		}

		return true;
	}
}
