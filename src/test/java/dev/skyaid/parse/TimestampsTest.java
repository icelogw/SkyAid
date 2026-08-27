package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Chat timestamp formatting")
class TimestampsTest {
	@Test
	void twentyFourHourIsZeroPadded() {
		assertEquals("09:07", Timestamps.format(LocalTime.of(9, 7), false));
		assertEquals("14:32", Timestamps.format(LocalTime.of(14, 32), false));
		assertEquals("00:05", Timestamps.format(LocalTime.of(0, 5), false));
		assertEquals("23:59", Timestamps.format(LocalTime.of(23, 59), false));
	}

	@Test
	void twelveHourDropsTheLeadingZeroAndAddsTheSuffix() {
		assertEquals("9:07am", Timestamps.format(LocalTime.of(9, 7), true));
		assertEquals("2:32pm", Timestamps.format(LocalTime.of(14, 32), true));
		assertEquals("11:59pm", Timestamps.format(LocalTime.of(23, 59), true));
	}

	@Test
	@DisplayName("midnight and noon both read as 12, not 0")
	void handlesTheHourTwelveEdges() {
		// A plain modulo turns both of these into "0:xx", which is the classic
		// twelve-hour clock bug.
		assertEquals("12:05am", Timestamps.format(LocalTime.of(0, 5), true));
		assertEquals("12:00pm", Timestamps.format(LocalTime.of(12, 0), true));
		assertEquals("12:59pm", Timestamps.format(LocalTime.of(12, 59), true));
		assertEquals("1:00pm", Timestamps.format(LocalTime.of(13, 0), true));
	}
}