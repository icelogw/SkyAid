package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Dungeon tab list parsing")
class DungeonTabTest {
	private static final char S = (char) 0x00A7;
	private static final char CHECK = (char) 0x2714;
	private static final char CROSS = (char) 0x2716;

	@Test
	void readsRunStateFromTabLines() {
		DungeonTab.State state = DungeonTab.parse(List.of(
				S + "6Secrets Found: " + S + "a42.5%",
				S + "cTeam Deaths: (2)",
				S + "6Crypts: " + S + "a3",
				S + "6Completed Rooms: " + S + "a12",
				S + "6Puzzles: " + S + "a(3)",
				" " + S + "7Water Board: [" + S + "a" + CHECK + S + "7]",
				" " + S + "7Higher Or Lower: [" + S + "c" + CROSS + S + "7]",
				" " + S + "7Tic Tac Toe: [" + S + "7]"));

		assertEquals(43, state.secretsPercent().orElseThrow());
		assertEquals(2, state.deaths().orElseThrow());
		assertEquals(3, state.crypts().orElseThrow());
		assertEquals(12, state.completedRooms().orElseThrow());
		assertEquals(3, state.puzzleCount().orElseThrow());
		assertEquals(3, state.puzzles().size());
		assertEquals("solved", DungeonTab.puzzleState(state, "Water Board").orElseThrow());
		assertEquals("failed", DungeonTab.puzzleState(state, "Higher Or Lower").orElseThrow());
		assertEquals("pending", DungeonTab.puzzleState(state, "Tic Tac Toe").orElseThrow());
	}

	@Test
	void ordinaryLobbyTabParsesToNothing() {
		DungeonTab.State state = DungeonTab.parse(List.of(
				"Players (34)", "SomePlayer", "AnotherPlayer"));

		assertTrue(state.secretsPercent().isEmpty());
		assertTrue(state.puzzles().isEmpty());
	}

	@Test
	@DisplayName("score model: perfect run grades S+, deaths and fails bite")
	void scoreModelBehaves() {
		DungeonTab.State clean = DungeonTab.parse(List.of(
				"Secrets Found: 100%", "Crypts: 5", "Mimic Dead: YES"));
		DungeonScore.Estimate perfect =
				DungeonScore.estimate(clean, OptionalLong.of(100));

		assertEquals("S+", perfect.grade());
		assertEquals(307, perfect.total());

		DungeonTab.State rough = DungeonTab.parse(List.of(
				"Secrets Found: 20%", "Team Deaths: (3)",
				"Puzzles: (1)", " Water Board: [" + CROSS + "]"));
		DungeonScore.Estimate bad = DungeonScore.estimate(rough, OptionalLong.of(50));

		assertEquals(80 + 38 + 100, bad.total());
	}
}
