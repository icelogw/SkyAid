package dev.skyaid.parse;

/**
 * Recognises Dwarven commission progress lines on the sidebar: a name, a
 * colon, then a percentage or DONE - "Mithril Miner: 45%", "Lava Springs
 * Titanium: DONE". Shape-based and zone-gated by the caller, because the
 * exact commission names are endless but the value format is stable.
 *
 * <p>Wording from ecosystem knowledge, not yet a capture - if a real Dwarven
 * sidebar shows a different shape, dump it and lock it into a fixture.
 */
public final class CommissionLines {
	private CommissionLines() {
	}

	public static boolean isCommission(String line) {
		int colon = line.indexOf(": ");

		if (colon <= 0 || colon + 2 >= line.length()) {
			return false;
		}

		String value = line.substring(colon + 2).trim();

		if (value.equals("DONE")) {
			return true;
		}

		if (!value.endsWith("%") || value.length() < 2) {
			return false;
		}

		// Digits with at most one dot, then the percent sign - and nothing
		// after it, which keeps "Cleared: 47% (118)" out of the net.
		boolean dot = false;

		for (int i = 0; i < value.length() - 1; i++) {
			char c = value.charAt(i);

			if (c == '.') {
				if (dot || i == 0) {
					return false;
				}

				dot = true;
			} else if (c < '0' || c > '9') {
				return false;
			}
		}

		return true;
	}
}
