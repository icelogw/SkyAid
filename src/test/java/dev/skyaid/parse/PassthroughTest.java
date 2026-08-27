package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkyAid hides Hypixel's sidebar by default but only understands the hub and
 * island layout. Anything it cannot place has to come back out verbatim, or a
 * dungeon run would simply have no sidebar at all.
 */
@DisplayName("Unrecognised sidebar lines")
class PassthroughTest {
	private static final char S = (char) 0x00A7;
	private static final char PIN = (char) 0xE067;
	private static final String TITLE = S + "e" + S + "lSKYBLOCK";

	@Test
	@DisplayName("a familiar hub sidebar leaves nothing over")
	void hubSidebarIsFullyUnderstood() {
		List<String> hub = List.of(
				S + "708/19/26 " + S + "8m4A",
				"",
				S + "7Late Spring 22nd",
				S + "7 4:20am",
				S + "7" + PIN + " " + S + "aYour Island",
				"",
				S + "7Purse: " + S + "67,884,267",
				S + "7Bits: " + S + "b15,615",
				"",
				S + "ewww.hypixel.net");

		assertTrue(ScoreboardParser.parse(TITLE, hub).extraLines().isEmpty());
	}

	@Test
	@DisplayName("a dungeon sidebar keeps the parts nothing here understands")
	void dungeonSidebarComesBackVerbatim() {
		// The case this exists for: none of these lines match any matcher, and with
		// the sidebar hidden they would otherwise be lost entirely.
		List<String> dungeon = List.of(
				S + "708/19/26 " + S + "8m4A",
				"",
				S + "7The Catacombs " + S + "7(" + S + "eF7" + S + "7)",
				S + "7Keys: " + S + "cWither: " + S + "a1 " + S + "8Blood: " + S + "a1",
				S + "7Time Elapsed: " + S + "a12m 34s",
				S + "7Cleared: " + S + "a62% " + S + "8(340)",
				"",
				S + "ewww.hypixel.net");

		List<String> extras = ScoreboardParser.parse(TITLE, dungeon).extraLines();

		assertEquals(4, extras.size());
		assertEquals("The Catacombs (F7)", extras.get(0));
		assertEquals("Keys: Wither: 1 Blood: 1", extras.get(1));
		assertEquals("Time Elapsed: 12m 34s", extras.get(2));
		assertEquals("Cleared: 62% (340)", extras.get(3));
	}

	@Test
	@DisplayName("blank lines and the advert are never passed through")
	void skipsBlanksAndPromo() {
		List<String> lines = List.of("", S + "ewww.hypixel.net", "", S + "7Mithril Powder: 1,234");

		List<String> extras = ScoreboardParser.parse(TITLE, lines).extraLines();

		assertEquals(1, extras.size());
		assertEquals("Mithril Powder: 1,234", extras.get(0));
	}

	@Test
	@DisplayName("recognised lines are not repeated as unrecognised ones")
	void claimedLinesAreNotDuplicated() {
		// The blank line matters: the slayer block claims the two lines after its
		// heading, and Hypixel separates its sections with one. Without it the
		// unknown line below would legitimately be read as the quest's status.
		List<String> lines = List.of(
				S + "7Purse: " + S + "6100",
				S + "7Slayer Quest",
				S + "cRevenant Horror II",
				"",
				S + "7Some unknown thing");

		List<String> extras = ScoreboardParser.parse(TITLE, lines).extraLines();

		assertEquals(List.of("Some unknown thing"), extras);
	}

	@Test
	@DisplayName("an unfamiliar sidebar cannot grow the HUD without limit")
	void capsThePassthrough() {
		List<String> many = new java.util.ArrayList<>();

		for (int i = 0; i < 30; i++) {
			many.add("Unknown line " + i);
		}

		assertEquals(8, ScoreboardParser.parse(TITLE, many).extraLines().size());
	}
}
