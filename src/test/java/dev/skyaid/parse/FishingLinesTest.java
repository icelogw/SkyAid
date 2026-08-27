package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Sea creature announcements")
class FishingLinesTest {
	@Test
	void recognisesNotableCreatures() {
		assertEquals("Thunder", FishingLines.creature(
				"You hear a massive rumble as Thunder emerges.").orElseThrow());
		assertEquals("Yeti", FishingLines.creature("What is this creature!?").orElseThrow());
	}

	@Test
	void ordinaryChatAnnouncesNothing() {
		assertTrue(FishingLines.creature("You caught a Squid!").isEmpty());
		assertTrue(FishingLines.creature("hello everyone").isEmpty());
	}

	@Test
	void doubleHookIsItsOwnSignal() {
		assertTrue(FishingLines.isDoubleHook("It's a Double Hook! Woot woot!"));
	}
}
