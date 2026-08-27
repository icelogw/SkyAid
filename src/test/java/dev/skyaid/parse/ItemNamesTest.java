package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Item name cleaning for market searches")
class ItemNamesTest {
	private static final char STAR = (char) 0x272A;

	@Test
	void stripsReforgeStarsAndLevels() {
		assertEquals("Superior Dragon Helmet", ItemNames.cleanForSearch(
				"Fierce Superior Dragon Helmet " + STAR + STAR + STAR));
		assertEquals("Ender Dragon", ItemNames.cleanForSearch("[Lvl 100] Ender Dragon"));
		assertEquals("Hyperion", ItemNames.cleanForSearch("Heroic Hyperion " + STAR));
	}

	@Test
	@DisplayName("legacy colour codes leave no stray digit behind")
	void stripsColourCodesEntirely() {
		// The section sign is not letter/digit but its colour digit is - the
		// field bug was "6Superior Dragon Helmet" reaching the search.
		String sect = String.valueOf((char) 0x00A7);
		assertEquals("Superior Dragon Helmet", ItemNames.cleanForSearch(
				sect + "6Superior Dragon Helmet"));
		assertEquals("Ender Dragon", ItemNames.cleanForSearch(
				sect + "7[Lvl 100] " + sect + "6Ender Dragon"));
	}

	@Test
	@DisplayName("plain names and reforge-like single words survive")
	void leavesCleanNamesAlone() {
		assertEquals("Aspect of the End", ItemNames.cleanForSearch("Aspect of the End"));
		assertEquals("Sharp", ItemNames.cleanForSearch("Sharp"));
	}
}
