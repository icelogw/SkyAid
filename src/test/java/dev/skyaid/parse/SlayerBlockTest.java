package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slayer block matters because SkyAid can hide Hypixel's sidebar: it is the
 * one section with no other home on screen, so if it fails to parse the player
 * simply loses it.
 */
@DisplayName("Slayer block parsing")
class SlayerBlockTest {
	private static final char S = (char) 0x00A7;
	private static final String TITLE = S + "e" + S + "lSKYBLOCK";

	/** Mirrors the captured sidebar, slayer block and promo line included. */
	private static List<String> withSlayer() {
		return List.of(
				S + "708/19/26 " + S + "8m4A",
				"",
				S + "7Late Spring 21st",
				S + "7 9:40am",
				"",
				S + "7Purse: " + S + "67,884,262",
				"",
				S + "7Slayer Quest",
				S + "cRevenant Horror II",
				S + "aBoss slain!",
				"",
				S + "ewww.hypixel.net");
	}

	@Test
	void readsQuestNameAndStatus() {
		SkyblockState state = ScoreboardParser.parse(TITLE, withSlayer());

		assertEquals("Revenant Horror II", state.slayerQuest().orElseThrow());
		assertEquals("Boss slain!", state.slayerStatus().orElseThrow());
	}

	@Test
	@DisplayName("the block is absent when no quest is active")
	void absentWithoutAQuest() {
		List<String> noQuest = List.of(
				S + "7Purse: " + S + "6100",
				"",
				S + "ewww.hypixel.net");

		SkyblockState state = ScoreboardParser.parse(TITLE, noQuest);

		assertTrue(state.slayerQuest().isEmpty());
		assertTrue(state.slayerStatus().isEmpty());
	}

	@Test
	@DisplayName("a quest with only a progress line still parses")
	void handlesASingleFollowingLine() {
		List<String> oneLine = List.of(
				S + "7Slayer Quest",
				S + "cRevenant Horror IV",
				"",
				S + "ewww.hypixel.net");

		SkyblockState state = ScoreboardParser.parse(TITLE, oneLine);

		assertEquals("Revenant Horror IV", state.slayerQuest().orElseThrow());
		assertTrue(state.slayerStatus().isEmpty());
	}

	@Test
	@DisplayName("the promo line is never treated as slayer content or a location")
	void promoIsIgnored() {
		SkyblockState state = ScoreboardParser.parse(TITLE, withSlayer());

		assertEquals("Boss slain!", state.slayerStatus().orElseThrow());
		assertTrue(state.location().isEmpty());
	}

	@Test
	@DisplayName("the rest of the sidebar still parses alongside a slayer block")
	void doesNotDisturbOtherFields() {
		SkyblockState state = ScoreboardParser.parse(TITLE, withSlayer());

		assertEquals(7_884_262L, state.purse().orElseThrow());
		assertEquals("m4A", state.serverId().orElseThrow());
		assertEquals("Late Spring 21st", state.date().orElseThrow());
	}
}
