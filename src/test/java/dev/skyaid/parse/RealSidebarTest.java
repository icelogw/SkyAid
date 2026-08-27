package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests built from a real Hypixel Skyblock sidebar captured in game.
 *
 * <p>The capture showed three defects at once: the purse truncated from 7,884,262
 * to 78,842, and the location and server id missing entirely. Each has a test
 * here so none of them can come back quietly.
 */
@DisplayName("Real captured Hypixel sidebar")
class RealSidebarTest {
	private static final char S = (char) 0x00A7;

	/**
	 * U+2063 INVISIBLE SEPARATOR, standing in for the padding Hypixel wedges into
	 * lines. Chosen deliberately: the old stripper worked from a hardcoded list of
	 * invisible characters that did not include this one, so it survived into the
	 * number and truncated it. The parser now removes anything Unicode classifies
	 * as a format character, so the exact code point no longer matters.
	 */
	private static final String PAD = String.valueOf((char) 0x2063);

	/** The map-pin glyph the live sidebar actually used, as a surrogate pair. */
	private static final String PIN = new String(Character.toChars(0x1F4CD));

	private static final String TITLE = S + "e" + S + "lSKYBLOCK";

	/**
	 * Mirrors the captured sidebar. The purse is deliberately split by padding
	 * mid-number, which is what truncated it: the old parser stopped at the
	 * padding and read 7,884,2 as 78,842.
	 */
	private static List<String> captured() {
		return List.of(
				S + "708/19/26 " + S + "8m93G",
				"",
				S + "7Late Spring 19th",
				S + "7 2:40am",
				PIN + " " + S + "aVillage",
				"",
				S + "7Purse: " + S + "67,884,2" + PAD + "62",
				S + "7Bits: " + S + "b15,615",
				"",
				S + "7Slayer Quest",
				S + "cRevenant Horror II",
				S + "aBoss slain!",
				S + "ewww.hypixel.net");
	}

	@Test
	@DisplayName("purse is not truncated by padding wedged inside the number")
	void purseSurvivesPaddingInsideTheNumber() {
		assertEquals(7_884_262L,
				ScoreboardParser.parse(TITLE, captured()).purse().orElseThrow());
	}

	@Test
	@DisplayName("location is found whatever glyph Hypixel prefixes it with")
	void locationIsGlyphAgnostic() {
		assertEquals("Village",
				ScoreboardParser.parse(TITLE, captured()).location().orElseThrow());
	}

	@Test
	void serverIdIsReadFromTheDateLine() {
		assertEquals("m93G",
				ScoreboardParser.parse(TITLE, captured()).serverId().orElseThrow());
	}

	@Test
	void readsTheRestOfTheCaptureCorrectly() {
		SkyblockState state = ScoreboardParser.parse(TITLE, captured());

		assertTrue(state.inSkyblock());
		assertEquals(15_615L, state.bits().orElseThrow());
		assertEquals("Late Spring 19th", state.date().orElseThrow());
		assertEquals("2:40am", state.time().orElseThrow());
	}

	/**
	 * A second capture, 2026-08-20, taken during the Carnival event. Every line
	 * carries a trailing padding code, the purse and the slayer name are split by
	 * padding mid-word, and the location glyph is U+E067 (private use) this time.
	 */
	private static List<String> carnivalCapture() {
		return List.of(
				"  " + S + "!",
				" Early Summer 30" + S + "zth",
				" " + S + "79:20am " + S + "e" + (char) 0x2600 + S + "y",
				" " + S + "7" + (char) 0xE067 + " " + S + "bVillage" + S + "x",
				"     " + S + "w",
				"Purse: " + S + "67,884,2" + S + "v" + S + "672",
				"Bits: " + S + "b15,615" + S + "u",
				"         " + S + "t",
				"Slayer Quest" + S + "s",
				S + "eRevenant Horro" + S + "q" + S + "er II",
				S + "aBoss slain!" + S + "p",
				"             " + S + "j",
				S + "eCarnival" + S + "f 112" + S + "i" + S + "f:17:10",
				"                " + S + "h");
	}

	@Test
	@DisplayName("the 2026-08-20 capture parses exactly as it did live")
	void carnivalCaptureParses() {
		SkyblockState state = ScoreboardParser.parse("SKYBLOCK", carnivalCapture());

		assertTrue(state.inSkyblock());
		assertEquals(7_884_272L, state.purse().orElseThrow());
		assertEquals(15_615L, state.bits().orElseThrow());
		assertEquals("Village", state.location().orElseThrow());
		assertEquals("Early Summer 30th", state.date().orElseThrow());
		assertEquals("9:20am", state.time().orElseThrow());
		assertEquals("Revenant Horror II", state.slayerQuest().orElseThrow());
		assertEquals("Boss slain!", state.slayerStatus().orElseThrow());
	}

	@Test
	@DisplayName("the server id survives padding wedged inside it")
	void serverIdSurvivesPadding() {
		// Captured 2026-08-20 in the Dungeon Hub.
		List<String> lines = List.of(S + "708/20/26 " + S + "8m64" + S + "z" + S + "8DF");

		assertEquals("m64DF",
				ScoreboardParser.parse("SKYBLOCK", lines).serverId().orElseThrow());
	}

	@Test
	@DisplayName("a trailing figure after the server id does not corrupt it")
	void serverIdSurvivesTrailingFigures() {
		// Captured 2026-08-20 on a solo Entrance run: the date line carried an
		// extra " 102,66" after the id.
		List<String> lines = List.of(
				S + "708/20/26 " + S + "8m30" + S + "y" + S + "8DW 102,66");

		assertEquals("m30DW",
				ScoreboardParser.parse("SKYBLOCK", lines).serverId().orElseThrow());
	}

	@Test
	@DisplayName("a just-earned marker after the purse does not corrupt it")
	void purseGainMarkerIsIgnored() {
		// Captured 2026-08-20: Hypixel appends "(+25)" right after a pickup.
		List<String> lines = List.of(
				"Purse: " + S + "67,887,9" + S + "v" + S + "667 " + S + "e(+25)");

		assertEquals(7_887_967L,
				ScoreboardParser.parse("SKYBLOCK", lines).purse().orElseThrow());
	}

	@Test
	@DisplayName("the Carnival timer passes through as an extra line, not the clock")
	void carnivalTimerPassesThrough() {
		SkyblockState state = ScoreboardParser.parse("SKYBLOCK", carnivalCapture());

		assertTrue(state.extraLines().stream()
						.anyMatch(line -> line.contains("Carnival")),
				"expected the Carnival timer in extras, got: " + state.extraLines());

		// 112:17:10 must not be mistaken for the in-game time.
		assertEquals("9:20am", state.time().orElseThrow());
	}

	/** The Catacombs pre-run lobby, captured 2026-08-20. */
	private static List<String> catacombsLobbyCapture() {
		return List.of(
				S + "708/20/26 " + S + "8m19" + S + "x" + S + "81DP",
				"  " + S + "w",
				" Summer 8th" + S + "v",
				" " + S + "71:40am" + S + "u",
				" " + S + "7" + (char) 0xE067 + " " + S + "cThe Catac" + S + "t"
						+ S + "combs " + S + "7(F2)",
				"     " + S + "s",
				S + "a[B] " + S + "bIcelogw " + S + "q" + S + "7[Lv7]",
				S + "a[B] " + S + "aRapaces1" + S + "p" + S + "a6_ " + S + "7[Lv6]",
				"         " + S + "j",
				"Starting in: " + S + "a0" + S + "i" + S + "a:03",
				"            " + S + "h",
				S + "ewww.hypixel.ne" + S + "g" + S + "et");
	}

	@Test
	@DisplayName("the Catacombs lobby parses as captured: absent is not zero")
	void catacombsLobbyParses() {
		SkyblockState state = ScoreboardParser.parse("SKYBLOCK", catacombsLobbyCapture());

		assertTrue(state.inSkyblock());
		assertEquals("The Catacombs (F2)", state.location().orElseThrow());
		assertEquals("F2", state.dungeonFloor().orElseThrow());
		assertEquals("m191DP", state.serverId().orElseThrow());

		// Dungeons hide the purse and bits; the parser must report absence, not 0.
		assertTrue(state.purse().isEmpty());
		assertTrue(state.bits().isEmpty());

		// The party list and countdown pass through for the HUD to show.
		assertEquals(List.of("[B] Icelogw [Lv7]", "[B] Rapaces16_ [Lv6]", "Starting in: 0:03"),
				state.extraLines());
	}

	@Test
	@DisplayName("the dungeon start countdown reads as an event timer")
	void dungeonCountdownIsATimer() {
		assertTrue(EventTimers.isTimer("Starting in: 0:03"));
	}

	@Test
	@DisplayName("promo and quest lines are not mistaken for the location")
	void ignoresPromoAndQuestLines() {
		List<String> noLocation = List.of(
				S + "7Slayer Quest",
				S + "aBoss slain!",
				S + "ewww.hypixel.net",
				S + "7Purse: " + S + "6100");

		assertTrue(ScoreboardParser.parse(TITLE, noLocation).location().isEmpty());
	}

	@Test
	@DisplayName("an unknown section-sign code is stripped, not left behind")
	void stripsUnknownFormattingCodes() {
		// The old stripper matched a fixed set of code letters and would leave a
		// stray sign in the middle of the number if Hypixel used another one.
		List<String> unknownCode = List.of("Purse: 1,234" + S + "z" + "567");

		assertEquals(1_234_567L,
				ScoreboardParser.parse(TITLE, unknownCode).purse().orElseThrow());
	}
}
