package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Blaze health tags")
class BlazeTagsTest {
	private static final char S = (char) 0x00A7;
	private static final String HEART = String.valueOf((char) 0x2764);

	@Test
	void readsCurrentOverMaxTags() {
		assertEquals(OptionalLong.of(20_000_000), BlazeTags.currentHealth(
				S + "e[Lv15] " + S + "cBlaze " + S + "a20,000,000" + S + "8/"
						+ S + "a25,000,000" + S + "c" + HEART));
	}

	@Test
	void readsCurrentOnlyTags() {
		assertEquals(OptionalLong.of(19_340), BlazeTags.currentHealth(
				S + "cBlaze " + S + "a19,340" + S + "c" + HEART));
	}

	@Test
	void ignoresOtherMobTags() {
		assertTrue(BlazeTags.currentHealth(
				"[Lv15] Lost Adventurer 1,000,000/1,000,000" + HEART).isEmpty());
		assertTrue(BlazeTags.currentHealth("Blaze").isEmpty());
		assertTrue(BlazeTags.currentHealth("").isEmpty());
	}
}
