package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Slayer boss timing")
class SlayerTimerTest {
	@Test
	void timesAFightFromBossUpToSlain() {
		SlayerTimer timer = new SlayerTimer();

		timer.observe("Slay 3 more!", 1_000);
		assertTrue(timer.bossUpFor(1_000).isEmpty());

		timer.observe(SlayerTimer.BOSS_UP, 10_000);
		timer.observe(SlayerTimer.BOSS_UP, 22_000);
		assertEquals(12_000, timer.bossUpFor(22_000).orElseThrow());

		timer.observe(SlayerTimer.BOSS_SLAIN, 44_000);
		assertTrue(timer.bossUpFor(44_000).isEmpty());
		assertEquals(34_000, timer.lastKill().orElseThrow());
	}

	@Test
	@DisplayName("a vanished quest stops the clock without recording a kill")
	void abandonedFightRecordsNothing() {
		SlayerTimer timer = new SlayerTimer();

		timer.observe(SlayerTimer.BOSS_UP, 5_000);
		timer.observe(null, 9_000);

		assertTrue(timer.bossUpFor(9_000).isEmpty());
		assertTrue(timer.lastKill().isEmpty());
	}

	@Test
	void lastKillSurvivesUntilTheNextFight() {
		SlayerTimer timer = new SlayerTimer();

		timer.observe(SlayerTimer.BOSS_UP, 0);
		timer.observe(SlayerTimer.BOSS_SLAIN, 30_000);
		timer.observe(null, 60_000);
		assertEquals(30_000, timer.lastKill().orElseThrow());

		timer.observe(SlayerTimer.BOSS_UP, 90_000);
		assertEquals(30_000, timer.lastKill().orElseThrow());
		assertEquals(5_000, timer.bossUpFor(95_000).orElseThrow());
	}

	@Test
	void formatsFightLengths() {
		assertEquals("34s", SlayerTimer.format(34_500));
		assertEquals("1m 12s", SlayerTimer.format(72_000));
	}
}
