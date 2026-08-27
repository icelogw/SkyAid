package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Skyblock action bar parsing")
class ActionBarParserTest {
	private static final char S = (char) 0x00A7;

	/**
	 * A private-use code point, standing in for the glyphs Hypixel draws from its
	 * own font. A real sidebar capture showed the location pin as U+E067, so the
	 * symbols beside these numbers cannot be assumed to be any particular emoji -
	 * which is why parsing anchors on the words instead.
	 */
	private static final char GLYPH = (char) 0xE067;

	@Test
	void readsHealthDefenceAndMana() {
		String bar = S + "c873/873" + GLYPH
				+ "     " + S + "a351" + GLYPH + " Defense"
				+ "     " + S + "b234/234" + GLYPH + " Mana";

		ActionBarState state = ActionBarParser.parse(bar);

		assertEquals(873L, state.health().orElseThrow());
		assertEquals(873L, state.maxHealth().orElseThrow());
		assertEquals(351L, state.defense().orElseThrow());
		assertEquals(234L, state.mana().orElseThrow());
		assertEquals(234L, state.maxMana().orElseThrow());
	}

	@Test
	@DisplayName("parses whatever glyph Hypixel uses, including none at all")
	void glyphIndependent() {
		ActionBarState state = ActionBarParser.parse("873/873 351 Defense 234/234 Mana");

		assertEquals(351L, state.defense().orElseThrow());
		assertEquals(234L, state.mana().orElseThrow());
		assertEquals(873L, state.health().orElseThrow());
	}

	@Test
	@DisplayName("segments Hypixel replaces with an XP popup are simply absent")
	void handlesMissingSegments() {
		String bar = S + "c100/100" + GLYPH
				+ "     " + S + "3+12.3 Farming (1,234/5,000)"
				+ "     " + S + "b50/50" + GLYPH + " Mana";

		ActionBarState state = ActionBarParser.parse(bar);

		assertEquals(100L, state.health().orElseThrow());
		assertEquals(50L, state.mana().orElseThrow());
		assertTrue(state.defense().isEmpty());
	}

	@Test
	@DisplayName("mana alone is not mistaken for health")
	void manaIsNotReadAsHealth() {
		ActionBarState state = ActionBarParser.parse("234/234 Mana");

		assertEquals(234L, state.mana().orElseThrow());
		assertTrue(state.health().isEmpty());
	}

	@Test
	void returnsEmptyStateForBlankInput() {
		assertEquals(ActionBarState.EMPTY, ActionBarParser.parse(""));
		assertEquals(ActionBarState.EMPTY, ActionBarParser.parse(null));
	}

	@Test
	@DisplayName("dungeon bars carry the room's secrets count")
	void readsSecretsCount() {
		// Shape from the real mid-run capture: the secrets segment rides on
		// the same bar as health, defence and mana.
		String bar = S + "c1,199/1,824" + GLYPH
				+ "     " + S + "a260" + GLYPH + " Defense"
				+ "     " + S + "b191/195" + GLYPH + " Mana"
				+ "     " + S + "71/6 Secrets";

		ActionBarState state = ActionBarParser.parse(bar);

		assertEquals(1L, state.secretsFound().orElseThrow());
		assertEquals(6L, state.secretsTotal().orElseThrow());
		assertEquals(1199L, state.health().orElseThrow());
		assertEquals(195L, state.maxMana().orElseThrow());
	}

	@Test
	@DisplayName("a secrets count alone is not mistaken for health")
	void secretsAreNotReadAsHealth() {
		ActionBarState state = ActionBarParser.parse("3/10 Secrets");

		assertEquals(3L, state.secretsFound().orElseThrow());
		assertEquals(10L, state.secretsTotal().orElseThrow());
		assertTrue(state.health().isEmpty());
	}
}
