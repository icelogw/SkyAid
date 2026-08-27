package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Skill levelling curve")
class SkillsTest {
	@Test
	void levelBoundariesAreExact() {
		assertEquals(0.0, Skills.levelFor(0, 60));
		assertEquals(1.0, Skills.levelFor(50, 60));
		assertEquals(2.0, Skills.levelFor(175, 60));
	}

	@Test
	void partialProgressIsFractional() {
		// Halfway through level 1's 50 XP.
		assertEquals(0.5, Skills.levelFor(25, 60));

		// Level 1 plus half of level 2's 125 XP.
		assertEquals(1.5, Skills.levelFor(50 + 62.5, 60));
	}

	@Test
	@DisplayName("XP past the cap does not invent levels")
	void capsHold() {
		assertEquals(50.0, Skills.levelFor(1_000_000_000, 50));
		assertEquals(60.0, Skills.levelFor(1_000_000_000, 60));
	}
}
