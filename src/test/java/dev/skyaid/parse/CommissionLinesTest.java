package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Dwarven commission line shapes")
class CommissionLinesTest {
	@Test
	void percentagesAndDoneCount() {
		assertTrue(CommissionLines.isCommission("Mithril Miner: 45%"));
		assertTrue(CommissionLines.isCommission("Goblin Slayer: 87.5%"));
		assertTrue(CommissionLines.isCommission("Lava Springs Titanium: DONE"));
	}

	@Test
	@DisplayName("dungeon and misc percent lines stay out of the net")
	void otherShapesDoNot() {
		assertFalse(CommissionLines.isCommission("Cleared: 47% (118)"));
		assertFalse(CommissionLines.isCommission("Time Elapsed: 07m 21s"));
		assertFalse(CommissionLines.isCommission("Mithril Miner: 45%%"));
		assertFalse(CommissionLines.isCommission("no colon here 45%"));
		assertFalse(CommissionLines.isCommission("Trailing: "));
	}
}
