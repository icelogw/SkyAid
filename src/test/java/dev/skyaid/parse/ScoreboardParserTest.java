package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixtures here mirror real Hypixel sidebars, formatting codes and all. When
 * Hypixel rewords the sidebar, these are the tests that should fail first.
 */
@DisplayName("Skyblock sidebar parsing")
class ScoreboardParserTest {
	private static final char S = (char) 0x00A7;
	private static final char ZONE = (char) 0x23E3;

	private static final String TITLE = S + "e" + S + "lSKYBLOCK" + S + "9 CO-OP";

	private static List<String> hubSidebar() {
		return List.of(
				S + "711/12/24 " + S + "8m123AB",
				"",
				" " + S + "7" + ZONE + " " + S + "aVillage",
				S + "7Late Summer 21st",
				S + "7 3:40am",
				"",
				S + "7Purse: " + S + "61,234,567",
				S + "7Bits: " + S + "b1,234");
	}

	@Test
	void readsEveryFieldFromARealHubSidebar() {
		SkyblockState state = ScoreboardParser.parse(TITLE, hubSidebar());

		assertTrue(state.inSkyblock());
		assertEquals("Village", state.location().orElseThrow());
		assertEquals("m123AB", state.serverId().orElseThrow());
		assertEquals(1_234_567L, state.purse().orElseThrow());
		assertEquals(1_234L, state.bits().orElseThrow());
		assertEquals("Late Summer 21st", state.date().orElseThrow());
		assertEquals("3:40am", state.time().orElseThrow());
	}

	@Test
	@DisplayName("ignores the coin-gain suffix Hypixel appends to the purse")
	void parsesPurseWithGainSuffix() {
		List<String> lines = List.of(S + "7Purse: " + S + "61,234,567 " + S + "a(+123)");
		assertEquals(1_234_567L, ScoreboardParser.parse(TITLE, lines).purse().orElseThrow());
	}

	@Test
	@DisplayName("reads the piggy-bank label as the purse")
	void parsesPiggyVariant() {
		List<String> lines = List.of(S + "7Piggy: " + S + "6500");
		assertEquals(500L, ScoreboardParser.parse(TITLE, lines).purse().orElseThrow());
	}

	@Test
	@DisplayName("a missing line yields an empty field, never a misleading zero")
	void absentFieldsStayEmpty() {
		// Bits are hidden outside the hub. The HUD must be able to hide the readout
		// rather than render "Bits: 0", which would be wrong.
		List<String> lines = List.of(S + "7Purse: " + S + "6100");
		SkyblockState state = ScoreboardParser.parse(TITLE, lines);

		assertTrue(state.purse().isPresent());
		assertTrue(state.bits().isEmpty());
		assertTrue(state.location().isEmpty());
	}

	@Test
	void detectsNonSkyblockAndEmptySidebars() {
		assertFalse(ScoreboardParser.parse(S + "eBED WARS", hubSidebar()).inSkyblock());
		assertFalse(ScoreboardParser.parse(TITLE, null).inSkyblock());
		assertTrue(ScoreboardParser.parse(TITLE, List.of()).purse().isEmpty());
	}

	@Test
	@DisplayName("parses regardless of line order")
	void orderIndependent() {
		SkyblockState state = ScoreboardParser.parse(TITLE, hubSidebar().reversed());

		assertEquals("Village", state.location().orElseThrow());
		assertEquals(1_234_567L, state.purse().orElseThrow());
		assertEquals("m123AB", state.serverId().orElseThrow());
	}

	@Test
	@DisplayName("the Skyblock date is not mistaken for a server id")
	void dateIsNotAServerId() {
		List<String> lines = List.of(S + "7Late Summer 21st");
		SkyblockState state = ScoreboardParser.parse(TITLE, lines);

		assertEquals("Late Summer 21st", state.date().orElseThrow());
		assertTrue(state.serverId().isEmpty());
	}
}
