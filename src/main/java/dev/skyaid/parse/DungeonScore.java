package dev.skyaid.parse;

import java.util.OptionalLong;

/**
 * The community's dungeon score model, computed from tab-list values plus the
 * sidebar clear percentage. Estimates only: Hypixel never shows the live
 * number, which is exactly why every dungeon mod computes it.
 *
 * <p>Formula per the community's reverse engineering (unverified against this
 * patch): skill = 100 - 14 per failed-or-incomplete puzzle - 2 per death,
 * floored at 20; exploration = 60 * clear% + 40 * min(secrets%, 100%);
 * speed is ~100 for any reasonable clear and is assumed 100; bonus = crypts
 * (capped 5) + 2 for a dead mimic. S is 270+, S+ is 300+.
 */
public final class DungeonScore {
	public record Estimate(long total, String grade) {
	}

	private DungeonScore() {
	}

	public static Estimate estimate(DungeonTab.State tab, OptionalLong clearPercent) {
		long deaths = tab.deaths().orElse(0);
		long failedPuzzles = tab.puzzles().stream()
				.filter(puzzle -> !puzzle.state().equals("solved"))
				.count();

		long skill = Math.max(20, 100 - 14 * failedPuzzles - 2 * deaths);

		double clear = clearPercent.orElse(0) / 100.0;
		double secrets = Math.min(1.0, tab.secretsPercent().orElse(0) / 100.0);
		long exploration = Math.round(60 * clear + 40 * secrets);

		long speed = 100;
		long bonus = Math.min(5, tab.crypts().orElse(0)) + (tab.mimicDead() ? 2 : 0);

		long total = skill + exploration + speed + bonus;
		return new Estimate(total, gradeOf(total));
	}

	private static String gradeOf(long total) {
		if (total >= 300) {
			return "S+";
		}

		if (total >= 270) {
			return "S";
		}

		if (total >= 230) {
			return "A";
		}

		if (total >= 160) {
			return "B";
		}

		return "C";
	}
}
