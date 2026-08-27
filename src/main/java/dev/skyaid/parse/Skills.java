package dev.skyaid.parse;

/**
 * The Skyblock skill levelling curve: XP needed for each level, 1 to 60.
 *
 * <p>Lives here, free of Minecraft types, because turning raw XP into "level
 * 32.4" is exactly the kind of arithmetic that looks right until someone sits
 * on a level boundary. The table is Hypixel's published curve.
 */
public final class Skills {
	private static final long[] XP_PER_LEVEL = {
			50, 125, 200, 300, 500, 750, 1_000, 1_500, 2_000, 3_500,
			5_000, 7_500, 10_000, 15_000, 20_000, 30_000, 50_000, 75_000, 100_000, 200_000,
			300_000, 400_000, 500_000, 600_000, 700_000, 800_000, 900_000, 1_000_000, 1_100_000,
			1_200_000,
			1_300_000, 1_400_000, 1_500_000, 1_600_000, 1_700_000, 1_800_000, 1_900_000,
			2_000_000, 2_100_000, 2_200_000,
			2_300_000, 2_400_000, 2_500_000, 2_600_000, 2_750_000, 2_900_000, 3_100_000,
			3_400_000, 3_700_000, 4_000_000,
			4_300_000, 4_600_000, 4_900_000, 5_200_000, 5_500_000, 5_800_000, 6_100_000,
			6_400_000, 6_700_000, 7_000_000};

	private Skills() {
	}

	/**
	 * The fractional level this much XP amounts to, honouring the skill's cap -
	 * XP keeps accruing past a cap, and counting it would report levels the
	 * skill cannot have.
	 */
	public static double levelFor(double xp, int cap) {
		double remaining = xp;
		int level = 0;
		int top = Math.min(cap, XP_PER_LEVEL.length);

		for (int i = 0; i < top; i++) {
			if (remaining < XP_PER_LEVEL[i]) {
				return level + remaining / XP_PER_LEVEL[i];
			}

			remaining -= XP_PER_LEVEL[i];
			level++;
		}

		return level;
	}
}
