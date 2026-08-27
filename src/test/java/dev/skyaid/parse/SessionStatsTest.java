package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Session tracking")
class SessionStatsTest {
	private static final OptionalLong NONE = OptionalLong.empty();

	private static OptionalLong coins(long value) {
		return OptionalLong.of(value);
	}

	@Test
	@DisplayName("nothing observed means nothing reported")
	void emptyBeforeAnyObservation() {
		SessionStats.Snapshot snapshot = new SessionStats().snapshot();

		assertFalse(snapshot.started());
		assertTrue(snapshot.coinsGained().isEmpty());
		assertTrue(snapshot.bitsGained().isEmpty());
		assertTrue(snapshot.coinsPerHour().isEmpty());
	}

	@Test
	@DisplayName("the first sighting is the baseline, not a gain")
	void firstSightingIsBaseline() {
		SessionStats session = new SessionStats();
		session.observe(0, coins(1_000_000), coins(500));

		assertEquals(OptionalLong.of(0), session.snapshot().coinsGained());
		assertEquals(OptionalLong.of(0), session.snapshot().bitsGained());
	}

	@Test
	void tracksGains() {
		SessionStats session = new SessionStats();
		session.observe(0, coins(1_000_000), coins(500));
		session.observe(250, coins(1_034_500), coins(740));

		assertEquals(OptionalLong.of(34_500), session.snapshot().coinsGained());
		assertEquals(OptionalLong.of(240), session.snapshot().bitsGained());
	}

	@Test
	@DisplayName("spending shows as a negative gain")
	void spendingGoesNegative() {
		SessionStats session = new SessionStats();
		session.observe(0, coins(1_000_000), NONE);
		session.observe(250, coins(400_000), NONE);

		assertEquals(OptionalLong.of(-600_000), session.snapshot().coinsGained());
	}

	@Test
	@DisplayName("an absent value keeps the last known figure - absent is not zero")
	void absentValueIsNotZero() {
		SessionStats session = new SessionStats();
		session.observe(0, coins(1_000_000), coins(500));

		// In a dungeon: no purse, no bits on the sidebar.
		session.observe(250, NONE, NONE);

		assertEquals(OptionalLong.of(0), session.snapshot().coinsGained());
		assertEquals(OptionalLong.of(0), session.snapshot().bitsGained());
	}

	@Test
	@DisplayName("bits stay unreported until their line has been seen")
	void bitsUnreportedUntilSeen() {
		SessionStats session = new SessionStats();
		session.observe(0, coins(1_000_000), NONE);
		session.observe(250, coins(1_100_000), NONE);

		assertTrue(session.snapshot().bitsGained().isEmpty());
	}

	@Test
	@DisplayName("time away does not count towards the session clock")
	void gapsAreCapped() {
		SessionStats session = new SessionStats();
		session.observe(0, coins(0), NONE);

		// Half an hour in a lobby, where nothing observes. On return, at most
		// MAX_GAP_MILLIS of it may count.
		session.observe(30 * 60_000, coins(0), NONE);

		assertEquals(SessionStats.MAX_GAP_MILLIS, session.snapshot().activeMillis());
	}

	@Test
	@DisplayName("coins/hour is withheld until a minute has been played")
	void rateWithheldEarly() {
		SessionStats session = new SessionStats();
		session.observe(0, coins(0), NONE);
		session.observe(500, coins(50_000), NONE);

		assertTrue(session.snapshot().coinsPerHour().isEmpty());
	}

	@Test
	void rateIsScaledToTheHour() {
		SessionStats session = new SessionStats();
		session.observe(0, coins(0), NONE);

		// Six minutes of steady half-second observations, gaining as we go.
		for (long t = 500; t <= 6 * 60_000; t += 500) {
			session.observe(t, coins(t), NONE);
		}

		SessionStats.Snapshot snapshot = session.snapshot();
		assertEquals(6 * 60_000, snapshot.activeMillis());

		// 360,000 coins over 6 minutes is 3.6M/hour.
		assertEquals(OptionalLong.of(3_600_000), snapshot.coinsPerHour());
	}

	@Test
	@DisplayName("a windowed rate reflects the window, not old gains")
	void windowedRateIgnoresOldGains() {
		SessionStats session = new SessionStats();

		// One coin per millisecond for the first minute, then nothing for eleven.
		for (long t = 0; t <= 12 * 60_000; t += 500) {
			session.observe(t, coins(Math.min(t, 60_000)), NONE);
		}

		// The whole-session average still carries the old burst...
		assertEquals(OptionalLong.of(300_000), session.snapshot(0).coinsPerHour());

		// ...but the last ten minutes contained no earnings at all.
		assertEquals(OptionalLong.of(0),
				session.snapshot(10 * 60_000).coinsPerHour());
	}

	@Test
	@DisplayName("a session shorter than the window falls back to the session average")
	void shortSessionFallsBackToSessionAverage() {
		SessionStats session = new SessionStats();

		for (long t = 0; t <= 2 * 60_000; t += 500) {
			session.observe(t, coins(t), NONE);
		}

		assertEquals(session.snapshot(0).coinsPerHour(),
				session.snapshot(10 * 60_000).coinsPerHour());
	}

	@Test
	@DisplayName("the windowed rate is withheld in the first minute too")
	void windowedRateGatedEarly() {
		SessionStats session = new SessionStats();
		session.observe(0, coins(0), NONE);
		session.observe(30_000, coins(50_000), NONE);

		assertTrue(session.snapshot(10 * 60_000).coinsPerHour().isEmpty());
	}

	@Test
	@DisplayName("a bank deposit is not counted as spending")
	void bankDepositCancelsOut() {
		SessionStats session = new SessionStats();
		session.observe(0, coins(1_000_000), NONE);

		// The chat confirmation and the purse drop arrive around the same time.
		session.observeBankTransfer(-400_000);
		session.observe(250, coins(600_000), NONE);

		assertEquals(OptionalLong.of(0), session.snapshot().coinsGained());
	}

	@Test
	@DisplayName("a withdrawal is not counted as earnings, in the rate either")
	void bankWithdrawalCancelsOutOfTheRate() {
		SessionStats session = new SessionStats();

		// Two idle minutes, then a 1M withdrawal, then two more idle minutes.
		for (long t = 0; t <= 2 * 60_000; t += 500) {
			session.observe(t, coins(500_000), NONE);
		}

		session.observeBankTransfer(1_000_000);

		for (long t = 2 * 60_000 + 500; t <= 4 * 60_000; t += 500) {
			session.observe(t, coins(1_500_000), NONE);
		}

		assertEquals(OptionalLong.of(0), session.snapshot().coinsGained());
		assertEquals(OptionalLong.of(0), session.snapshot(0).coinsPerHour());
		assertEquals(OptionalLong.of(0), session.snapshot(10 * 60_000).coinsPerHour());
	}

	@Test
	@DisplayName("real earnings still count with a transfer in the middle")
	void earningsSurviveATransfer() {
		SessionStats session = new SessionStats();
		session.observe(0, coins(1_000_000), NONE);

		// Earn 50k, then deposit 900k, then earn another 50k.
		session.observe(500, coins(1_050_000), NONE);
		session.observeBankTransfer(-900_000);
		session.observe(1_000, coins(150_000), NONE);
		session.observe(1_500, coins(200_000), NONE);

		assertEquals(OptionalLong.of(100_000), session.snapshot().coinsGained());
	}

	@Test
	void formatsDurations() {
		assertEquals("0s", snapshotAt(0).formattedDuration());
		assertEquals("38s", snapshotAt(38_000).formattedDuration());
		assertEquals("4m", snapshotAt(4 * 60_000 + 12_000).formattedDuration());
		assertEquals("1h 05m", snapshotAt(65 * 60_000).formattedDuration());
		assertEquals("2h 00m", snapshotAt(120 * 60_000).formattedDuration());
	}

	private static SessionStats.Snapshot snapshotAt(long activeMillis) {
		return new SessionStats.Snapshot(NONE, NONE, NONE, activeMillis);
	}
}
