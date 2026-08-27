package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Coordinates in chat")
class ChatCoordsTest {
	private static final char S = (char) 0x00A7;

	@Test
	@DisplayName("the share-location format parses, zone as the label")
	void parsesTheShareFormat() {
		ChatCoords.Shared shared = ChatCoords.parse(
				"Party > [MVP+] Notch: x: 123, y: 70, z: -45 (Village)").orElseThrow();

		assertEquals(123, shared.x());
		assertEquals(70, shared.y());
		assertEquals(-45, shared.z());
		assertEquals("Village", shared.label());
	}

	@Test
	@DisplayName("without a zone note, the sender's name is the label")
	void senderNameAsLabel() {
		ChatCoords.Shared shared = ChatCoords.parse(
				S + "9Party " + S + "8> " + S + "aNotch" + S + "f: x: 5, y: 64, z: 5")
				.orElseThrow();

		assertEquals("Notch", shared.label());
		assertEquals(5, shared.x());
	}

	@Test
	@DisplayName("\"max: 5\" is not an x coordinate")
	void axisLettersMustStartWords() {
		assertTrue(ChatCoords.parse("Party > Notch: max: 5, money: 3, size: 9").isEmpty());
	}

	@Test
	void incompleteCoordsAreIgnored() {
		assertTrue(ChatCoords.parse("Party > Notch: x: 123, y: 70").isEmpty());
		assertTrue(ChatCoords.parse("Party > Notch: on my way").isEmpty());
		assertTrue(ChatCoords.parse("").isEmpty());
	}

	@Test
	void hugeNumbersAreRefused() {
		assertTrue(ChatCoords.parse("x: 999999999999, y: 70, z: 5").isEmpty());
	}
}
