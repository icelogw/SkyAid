package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Formatting code stripping")
class FormatCodesTest {
	private static final char S = (char) 0x00A7;

	@Test
	void stripsClassicColourCodes() {
		assertEquals("Purse: 1,234", FormatCodes.strip(S + "7Purse: " + S + "61,234"));
	}

	@Test
	@DisplayName("strips the 1.16+ hex form, which is seven classic codes in a row")
	void stripsHexColourCodes() {
		String hex = S + "x" + S + "f" + S + "f" + S + "0" + S + "0" + S + "0" + S + "0";
		assertEquals("Bits: 500", FormatCodes.strip(hex + "Bits: 500"));
	}

	@Test
	@DisplayName("removes the invisible padding Hypixel uses to keep lines unique")
	void stripsInvisiblePadding() {
		String zeroWidth = String.valueOf((char) 0x200B);
		String rtlMark = String.valueOf((char) 0x200F);
		assertEquals("Bits: 500", FormatCodes.strip(zeroWidth + "Bits: 500" + rtlMark));
	}

	@Test
	void normalisesNonBreakingSpacesAndWhitespaceRuns() {
		String nbsp = String.valueOf((char) 0x00A0);
		assertEquals("Purse: 1,234", FormatCodes.strip("Purse:" + nbsp + nbsp + " 1,234"));
		assertEquals("a b", FormatCodes.strip("  a \t\n b  "));
	}

	@Test
	void returnsEmptyStringForNullOrEmptyInput() {
		assertEquals("", FormatCodes.strip(null));
		assertEquals("", FormatCodes.strip(""));
		assertEquals("", FormatCodes.strip(S + "7" + S + "6"));
	}
}
