package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Objective block parsing")
class ObjectiveBlockTest {
	private static final char S = (char) 0x00A7;
	private static final String TITLE = S + "e" + S + "lSKYBLOCK";

	@Test
	void readsTheQuestAndItsProgress() {
		List<String> lines = List.of(
				S + "7Objective",
				S + "fTalk to the Trapper",
				S + "7Reward: 1 Trapper Quest",
				"",
				S + "ewww.hypixel.net");

		SkyblockState state = ScoreboardParser.parse(TITLE, lines);

		assertEquals("Talk to the Trapper", state.objective().orElseThrow());
		assertEquals("Reward: 1 Trapper Quest", state.objectiveStatus().orElseThrow());
	}

	@Test
	@DisplayName("an objective and a slayer quest can both be present")
	void coexistsWithSlayer() {
		// Both blocks read the same way, so the risk is one swallowing the other's
		// lines when they sit next to each other.
		List<String> lines = List.of(
				S + "7Objective",
				S + "fTalk to the Trapper",
				"",
				S + "7Slayer Quest",
				S + "cRevenant Horror II",
				S + "aBoss slain!",
				"",
				S + "ewww.hypixel.net");

		SkyblockState state = ScoreboardParser.parse(TITLE, lines);

		assertEquals("Talk to the Trapper", state.objective().orElseThrow());
		assertTrue(state.objectiveStatus().isEmpty());
		assertEquals("Revenant Horror II", state.slayerQuest().orElseThrow());
		assertEquals("Boss slain!", state.slayerStatus().orElseThrow());
	}

	@Test
	@DisplayName("neither block leaves its lines in the passthrough")
	void blocksAreClaimed() {
		List<String> lines = List.of(
				S + "7Objective",
				S + "fTalk to the Trapper",
				"",
				S + "7Something unknown");

		assertEquals(List.of("Something unknown"),
				ScoreboardParser.parse(TITLE, lines).extraLines());
	}

	@Test
	void absentWhenNoQuestIsActive() {
		List<String> lines = List.of(S + "7Purse: " + S + "6100");
		SkyblockState state = ScoreboardParser.parse(TITLE, lines);

		assertTrue(state.objective().isEmpty());
		assertTrue(state.objectiveStatus().isEmpty());
	}
}
