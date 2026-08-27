package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Objective from the boss bars")
class BossBarsTest {
	private static final char S = (char) 0x00A7;

	@Test
	@DisplayName("reads the objective exactly as captured in game")
	void readsTheCapturedObjective() {
		// The 2026-08-20 screenshot: a boss bar, not a sidebar line.
		assertEquals(Optional.of("Talk to Fisherwoman Enid."),
				BossBars.objective(List.of("Objective: Talk to Fisherwoman Enid.")));
	}

	@Test
	void ignoresActualBossBars() {
		assertTrue(BossBars.objective(
				List.of("Revenant Horror II", "Enderman")).isEmpty());
	}

	@Test
	void survivesFormattingCodes() {
		assertEquals(Optional.of("Talk to Fisherwoman Enid."),
				BossBars.objective(List.of(
						S + "6" + S + "lObjective: " + S + "eTalk to Fisherwoman Enid.")));
	}

	@Test
	void findsTheObjectiveAmongOtherBars() {
		assertEquals(Optional.of("Reach the summit"),
				BossBars.objective(List.of(
						"Enderman", "Objective: Reach the summit", "Jerry")));
	}

	@Test
	@DisplayName("banners are classified; unknown bars are neither")
	void classifiesBanners() {
		assertTrue(BossBars.isObjective(
				S + "fObjective: " + S + "eTalk to " + S + "9Fisherwoman Enid" + S + "e."));
		assertTrue(BossBars.isAdvert(S + "dwww.HYPIXEL.net"));
		assertTrue(BossBars.isAdvert("STORE.HYPIXEL.NET - SUMMER SALE"));

		// A real encounter is neither, so the filter can never hide one.
		assertTrue(!BossBars.isObjective("Revenant Horror II"));
		assertTrue(!BossBars.isAdvert("Revenant Horror II"));

		// Talking about the server is not an advert; only the address is.
		assertTrue(!BossBars.isAdvert("Hypixel Level 42"));
	}

	@Test
	void nothingOnScreenMeansNoObjective() {
		assertTrue(BossBars.objective(List.of()).isEmpty());

		// A bare heading with no task is not an objective either.
		assertTrue(BossBars.objective(List.of("Objective:")).isEmpty());
	}
}
