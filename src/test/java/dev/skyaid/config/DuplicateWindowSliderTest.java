package dev.skyaid.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Only the label formatting is tested here. The slider itself needs Minecraft's
 * widget classes, but how a duration reads is plain logic and easy to get subtly
 * wrong at the minute boundaries.
 */
@DisplayName("Repeat window label")
class DuplicateWindowSliderTest {
	@Test
	void readsSecondsBelowAMinute() {
		assertEquals("5s", DuplicateWindowSlider.describe(5));
		assertEquals("45s", DuplicateWindowSlider.describe(45));
	}

	@Test
	@DisplayName("whole minutes drop the seconds part")
	void readsWholeMinutes() {
		assertEquals("1m", DuplicateWindowSlider.describe(60));
		assertEquals("5m", DuplicateWindowSlider.describe(300));
	}

	@Test
	void readsMixedMinutesAndSeconds() {
		assertEquals("1m 30s", DuplicateWindowSlider.describe(90));
		assertEquals("2m 5s", DuplicateWindowSlider.describe(125));
	}

	@Test
	@DisplayName("the range ends line up with the slider limits")
	void coversTheWholeRange() {
		assertEquals("5s", DuplicateWindowSlider.describe(DuplicateWindowSlider.MIN_SECONDS));
		assertEquals("5m", DuplicateWindowSlider.describe(DuplicateWindowSlider.MAX_SECONDS));
	}
}
