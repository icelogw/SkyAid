package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Dungeon party lines")
class DungeonLinesTest {
	/** Stands in for whatever glyph Hypixel uses; the code must not care. */
	private static final String HEART = String.valueOf((char) 0x2764);

	@Test
	@DisplayName("party health is shortened, glyph preserved")
	void shortensHealth() {
		assertEquals("[B] G00PED 1.6k" + HEART,
				DungeonLines.withShortHealth("[B] G00PED 1,586" + HEART, true));

		// As captured in a screenshot: sometimes there is no trailing glyph.
		assertEquals("[M] BearBear1234 4.3k",
				DungeonLines.withShortHealth("[M] BearBear1234 4,255", true));
	}

	@Test
	@DisplayName("small health values stay exact")
	void smallValuesUntouched() {
		assertEquals("[M] margielabunn 268" + HEART,
				DungeonLines.withShortHealth("[M] margielabunn 268" + HEART, true));
	}

	@Test
	@DisplayName("the dungeon progress lines are recognised, as captured mid-run")
	void recognisesDungeonStats() {
		// Exactly as the 2026-08-20 F3 dump showed them, codes stripped.
		assertEquals(true, DungeonLines.isDungeonStat(
				"Keys: " + (char) 0x25A0 + " " + (char) 0x2717
						+ " " + (char) 0x25A0 + " 0x"));
		assertEquals(true, DungeonLines.isDungeonStat("Time Elapsed: 34s"));
		assertEquals(true, DungeonLines.isDungeonStat("Cleared: 8% (14)"));

		assertEquals(false, DungeonLines.isDungeonStat("[B] G00PED 1,025" + HEART));
		assertEquals(false, DungeonLines.isDungeonStat("Starting in: 0:03"));
	}

	@Test
	@DisplayName("off means off, and non-party lines are never touched")
	void leavesEverythingElseAlone() {
		assertEquals("[B] G00PED 1,586" + HEART,
				DungeonLines.withShortHealth("[B] G00PED 1,586" + HEART, false));

		// The pre-run lobby shows levels, not health.
		assertEquals("[B] Icelogw [Lv7]",
				DungeonLines.withShortHealth("[B] Icelogw [Lv7]", true));

		assertEquals("Cleared: 20% (52)",
				DungeonLines.withShortHealth("Cleared: 20% (52)", true));
		assertEquals("Time Elapsed: 04m 44s",
				DungeonLines.withShortHealth("Time Elapsed: 04m 44s", true));
	}
}
