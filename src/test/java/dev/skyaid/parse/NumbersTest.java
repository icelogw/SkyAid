package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("HUD number formatting")
class NumbersTest {
	@Test
	void groupsDigitsWithCommas() {
		assertEquals("7,884,267", Numbers.group(7_884_267L));
		assertEquals("15,615", Numbers.group(15_615L));
		assertEquals("999", Numbers.group(999L));
		assertEquals("0", Numbers.group(0L));
		assertEquals("-1,500", Numbers.group(-1_500L));
	}

	@Test
	@DisplayName("round figures drop the trailing .0")
	void shortensRoundFigures() {
		assertEquals("10k", Numbers.shorten(10_000L));
		assertEquals("1k", Numbers.shorten(1_000L));
		assertEquals("2M", Numbers.shorten(2_000_000L));
	}

	@Test
	void keepsOneDecimalWhenItCarriesInformation() {
		assertEquals("15.6k", Numbers.shorten(15_615L));
		assertEquals("7.9M", Numbers.shorten(7_884_267L));
		assertEquals("1.5k", Numbers.shorten(1_500L));
	}

	@Test
	@DisplayName("values below a thousand are left alone")
	void leavesSmallNumbersAlone() {
		assertEquals("999", Numbers.shorten(999L));
		assertEquals("0", Numbers.shorten(0L));
		assertEquals("42", Numbers.shorten(42L));
	}

	@Test
	@DisplayName("rounding up into the next unit reads as that unit")
	void carriesAcrossUnitBoundaries() {
		// The trap: 999,999 rounds to 1000.0k, which should be 1M and not "1000k".
		assertEquals("1M", Numbers.shorten(999_999L));
		assertEquals("1B", Numbers.shorten(999_999_999L));

		// Just below the carry, the decimal is still the useful part.
		assertEquals("999.9k", Numbers.shorten(999_949L));
	}

	@Test
	void handlesBillionsAndNegatives() {
		assertEquals("2.5B", Numbers.shorten(2_500_000_000L));
		assertEquals("-7.9M", Numbers.shorten(-7_884_267L));
	}

	@Test
	void formatPicksTheRequestedForm() {
		assertEquals("15.6k", Numbers.format(15_615L, true));
		assertEquals("15,615", Numbers.format(15_615L, false));
	}
}
