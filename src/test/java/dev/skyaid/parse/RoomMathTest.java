package dev.skyaid.parse;

import dev.skyaid.parse.RoomMath.Cell;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomMathTest {
	@Test
	void cellCornersLandOnTheShiftedGrid() {
		// The dungeon grid: corners at multiples of 32 minus 8. Hypixel's
		// dungeon coordinates are negative, so negatives are what matter.
		assertEquals(new Cell(-8, -8), RoomMath.cellAt(-8, -8));
		assertEquals(new Cell(-8, -8), RoomMath.cellAt(0, 0));
		assertEquals(new Cell(-8, -8), RoomMath.cellAt(22, 22));
		assertEquals(new Cell(24, -8), RoomMath.cellAt(24, 5));
		assertEquals(new Cell(-40, -40), RoomMath.cellAt(-39, -12));
		assertEquals(new Cell(-72, -104), RoomMath.cellAt(-45, -80));
	}

	@Test
	void relativeRoundTripsInEveryDirection() {
		List<Cell> cells = List.of(new Cell(-40, -72));

		for (String direction : RoomMath.ALL_DIRECTIONS) {
			Cell corner = RoomMath.cornerFor(direction, cells);
			int[] relative = RoomMath.actualToRelative(-25, 70, -50, direction, corner);
			int[] back = RoomMath.relativeToActual(
					relative[0], relative[1], relative[2], direction, corner);

			assertEquals(-25, back[0], direction);
			assertEquals(70, back[1], direction);
			assertEquals(-50, back[2], direction);
		}
	}

	@Test
	void relativeCoordinatesStayInRoomBoundsForAllRotations() {
		// A block inside a 1x1 room must land in 0..30 whichever corner the
		// recorded origin uses - that is what makes rotation matching work.
		List<Cell> cells = List.of(new Cell(-40, -72));

		for (String direction : RoomMath.ALL_DIRECTIONS) {
			Cell corner = RoomMath.cornerFor(direction, cells);

			for (int[] probe : new int[][]{{-40, -72}, {-10, -42}, {-25, -57}}) {
				int[] relative = RoomMath.actualToRelative(
						probe[0], 70, probe[1], direction, corner);
				assertTrue(relative[0] >= 0 && relative[0] <= 30,
						direction + " x " + relative[0]);
				assertTrue(relative[2] >= 0 && relative[2] <= 30,
						direction + " z " + relative[2]);
			}
		}
	}

	@Test
	void sizesFollowCellArrangement() {
		assertEquals("1x1", RoomMath.size(List.of(new Cell(0, 0))));
		assertEquals("1x2", RoomMath.size(List.of(new Cell(0, 0), new Cell(32, 0))));
		assertEquals("1x3", RoomMath.size(List.of(
				new Cell(0, 0), new Cell(32, 0), new Cell(64, 0))));
		assertEquals("L-shape", RoomMath.size(List.of(
				new Cell(0, 0), new Cell(32, 0), new Cell(0, 32))));
		assertEquals("2x2", RoomMath.size(List.of(new Cell(0, 0),
				new Cell(32, 0), new Cell(0, 32), new Cell(32, 32))));
		assertEquals("1x4", RoomMath.size(List.of(new Cell(0, 0),
				new Cell(0, 32), new Cell(0, 64), new Cell(0, 96))));
	}

	@Test
	void rectanglesNeedTwoDirectionsAndSquaresFour() {
		assertEquals(4, RoomMath.possibleDirections(
				"1x1", List.of(new Cell(0, 0))).size());

		List<Cell> eastWest = List.of(new Cell(0, 0), new Cell(32, 0));
		assertEquals(List.of("NW", "SE"),
				RoomMath.possibleDirections("1x2", eastWest));

		List<Cell> northSouth = List.of(new Cell(0, 0), new Cell(0, 32));
		assertEquals(List.of("NE", "SW"),
				RoomMath.possibleDirections("1x2", northSouth));
	}

	@Test
	void lShapeMissingCornerNamesItsOnlyDirection() {
		// Missing SE corner (big x, big z) -> recorded as NE per the original.
		List<Cell> missingSe = List.of(
				new Cell(0, 0), new Cell(32, 0), new Cell(0, 32));
		assertEquals(List.of("NE"),
				RoomMath.possibleDirections("L-shape", missingSe));

		List<Cell> missingNw = List.of(
				new Cell(32, 0), new Cell(0, 32), new Cell(32, 32));
		assertEquals(List.of("SW"),
				RoomMath.possibleDirections("L-shape", missingNw));
	}

	@Test
	void doorwayBandOnlyCoversCentreStripsAtRoomEdges() {
		// Doorway: centre columns at the cell border, door-height band only.
		assertTrue(RoomMath.isDoorway(23, 70, 8));
		assertFalse(RoomMath.isDoorway(23, 80, 8));
		assertFalse(RoomMath.isDoorway(18, 70, 18));
	}

	@Test
	void packingMatchesTheRecordedFormat() {
		// shortToLong(1, 70, 2, 9800) computed with the original formula.
		assertEquals(((long) ((1 << 16) | 70) << 32) | ((2 << 16) | 9800),
				RoomMath.packBlock(1, 70, 2, 9800));
	}
}
