package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Garden lines and alignment")
class GardenLinesTest {
	@Test
	void claimsGardenPrefixes() {
		assertTrue(GardenLines.isGardenLine("Milestone: Wheat 14"));
		assertTrue(GardenLines.isGardenLine(" Pests: 2"));
		assertTrue(GardenLines.isGardenLine("Next Visitor: 4m 30s"));
		assertTrue(GardenLines.isGardenLine("Jacob's Contest"));
		assertTrue(GardenLines.isGardenLine("Copper: 53"));
		assertTrue(GardenLines.isGardenLine("Sowdust: 205,691"));
		assertFalse(GardenLines.isGardenLine("Purse: 1,000"));
		assertFalse(GardenLines.isGardenLine("Time Elapsed: 34s"));
	}

	@Test
	@DisplayName("tab widget stats are recognised and icon glyphs stripped")
	void tabStatsAndIcons() {
		assertTrue(GardenLines.isTabStat(" Garden Level: VII (64.8%)"));
		assertTrue(GardenLines.isTabStat("Farming Fortune: 240"));
		assertTrue(GardenLines.isTabStat(" Sugar Cane Fortune: 99"));
		assertFalse(GardenLines.isTabStat("Speed: 329"));
		assertFalse(GardenLines.isTabStat("Interest: 19 Hours"));
		assertEquals("Farming Fortune: 240",
				GardenLines.stripIcons("Farming Fortune: " + "240"));
	}

	@Test
	@DisplayName("angle shows the offset from the nearest row direction")
	void angleShowsNearestDirection() {
		assertEquals("Yaw 90.3 (+0.3 off W)  Pitch -3.0",
				GardenLines.angle(90.3f, -3.0f));
		assertEquals("Yaw 359.0 (-1.0 off S)  Pitch 0.0",
				GardenLines.angle(-1.0f, 0.0f));
		assertEquals("Yaw 270.0 (+0.0 off E)  Pitch 90.0",
				GardenLines.angle(270.0f, 90.0f));
	}
}
