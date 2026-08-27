package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Event timer lines")
class EventTimersTest {
	private static final char S = (char) 0x00A7;

	@Test
	@DisplayName("the captured Carnival line is a timer")
	void recognisesTheCapturedCarnivalLine() {
		// Exactly as the 2026-08-20 dump showed it, format codes and all.
		assertTrue(EventTimers.isTimer(
				S + "eCarnival" + S + "f 111" + S + "i" + S + "f:39:59"));
		assertTrue(EventTimers.isTimer("Carnival 111:39:59"));
	}

	@Test
	void recognisesShortClocks() {
		assertTrue(EventTimers.isTimer("Spooky Festival 58:05"));
	}

	@Test
	void ordinaryLinesAreNotTimers() {
		assertFalse(EventTimers.isTimer("Purse: 7,884,272"));
		assertFalse(EventTimers.isTimer("Boss slain!"));
		assertFalse(EventTimers.isTimer("9:20am"));
		assertFalse(EventTimers.isTimer(""));

		// A clock needs a name in front of it to be an event.
		assertFalse(EventTimers.isTimer("111:39:59"));
	}

	@Test
	void clockShapeIsStrict() {
		assertTrue(EventTimers.isClock("111:39:59"));
		assertTrue(EventTimers.isClock("58:05"));

		assertFalse(EventTimers.isClock("111:3:59"));
		assertFalse(EventTimers.isClock("1:22:33:44"));
		assertFalse(EventTimers.isClock("ab:cd"));
		assertFalse(EventTimers.isClock("39"));
	}
}
