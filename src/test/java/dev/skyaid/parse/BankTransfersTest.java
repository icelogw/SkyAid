package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Bank transfer messages")
class BankTransfersTest {
	private static final char S = (char) 0x00A7;

	@Test
	@DisplayName("the real captured wordings (2026-08-24 log) are matched")
	void readsCapturedWordings() {
		assertEquals(OptionalLong.of(-8_300_000), BankTransfers.intoPurse(
				"Deposited 8.3M coins! There's now 39.5M coins in the account!"));
		assertEquals(OptionalLong.of(1_000), BankTransfers.intoPurse(
				"Withdrew 1k coins! There's now 39.5M coins left in the account!"));
	}

	@Test
	@DisplayName("interest lands in the bank, not the purse - never corrected")
	void ignoresInterest() {
		assertTrue(BankTransfers.intoPurse(
				"You have just received 371,000 coins as interest in your co-op bank account!")
				.isEmpty());
	}

	@Test
	void readsWithdrawalsAsCoinsIn() {
		assertEquals(OptionalLong.of(1_000_000), BankTransfers.intoPurse(
				"You have withdrawn 1,000,000 coins! You now have 5,000,000 coins in your account!"));
	}

	@Test
	void readsDepositsAsCoinsOut() {
		assertEquals(OptionalLong.of(-500_000), BankTransfers.intoPurse(
				"You have deposited 500k coins! You now have 6,000,000 coins in your account!"));
	}

	@Test
	@DisplayName("abbreviated and decimal amounts are scaled")
	void readsAbbreviatedAmounts() {
		assertEquals(OptionalLong.of(1_500_000),
				BankTransfers.parseAmount("1.5M"));
		assertEquals(OptionalLong.of(2_000_000_000L),
				BankTransfers.parseAmount("2B"));
		assertEquals(OptionalLong.of(750),
				BankTransfers.parseAmount("750"));
	}

	@Test
	void survivesFormattingCodesAndSingularCoin() {
		assertEquals(OptionalLong.of(1), BankTransfers.intoPurse(
				S + "aYou have withdrawn " + S + "61" + " coin!"));
	}

	@Test
	void ignoresEverythingElse() {
		assertTrue(BankTransfers.intoPurse("You have 5,000,000 coins in your account!").isEmpty());
		assertTrue(BankTransfers.intoPurse("Jerry: deposit your coins!").isEmpty());
		assertTrue(BankTransfers.intoPurse("").isEmpty());

		// A mangled amount is refused rather than misread.
		assertTrue(BankTransfers.parseAmount("1.2.3").isEmpty());
		assertTrue(BankTransfers.parseAmount("abc").isEmpty());
		assertTrue(BankTransfers.parseAmount("").isEmpty());
	}
}
