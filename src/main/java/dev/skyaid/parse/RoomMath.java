package dev.skyaid.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The geometry of the Catacombs: rooms live on a 32-block grid (31 blocks of
 * room, 1 of gap), shifted by 8 since Skyblock 0.12.3, and every recorded room
 * is stored in one canonical orientation - so identifying a room means trying
 * its blocks against the data in up to four rotations.
 *
 * <p>Ported faithfully from Dungeon Rooms Mod's MapUtils/RoomDetectionUtils
 * (Quantizr, GPL-3.0) - including its exact rounding behaviour, because the
 * recorded data was built with these formulas and quirks and all. Pure math,
 * no Minecraft types, unit-testable offline.
 */
public final class RoomMath {
	/** The north-west ground corner of one 32x32 grid cell, in world x/z. */
	public record Cell(int x, int z) {
	}

	/** A rotation candidate: which physical corner the recorded origin sits in. */
	public static final String[] ALL_DIRECTIONS = {"NW", "NE", "SE", "SW"};

	private RoomMath() {
	}

	/** The grid cell containing a world position. */
	public static Cell cellAt(double x, double z) {
		// The +0.5 splits the border evenly; the +8 undoes Hypixel's grid
		// shift; the (int) casts mirror the original, quirks included.
		double shiftedX = x + 0.5 + 8;
		double shiftedZ = z + 0.5 + 8;
		int cornerX = (int) (shiftedX - Math.floorMod((int) shiftedX, 32));
		int cornerZ = (int) (shiftedZ - Math.floorMod((int) shiftedZ, 32));
		return new Cell(cornerX - 8, cornerZ - 8);
	}

	/**
	 * The room's shape from its cells: 1x1, 1x2, 1x3, 1x4, 2x2 or L-shape -
	 * the names double as the data's category folder names.
	 */
	public static String size(List<Cell> cells) {
		if (cells.size() == 1) {
			return "1x1";
		}

		if (cells.size() == 2) {
			return "1x2";
		}

		TreeSet<Integer> xs = new TreeSet<>();
		TreeSet<Integer> zs = new TreeSet<>();

		for (Cell cell : cells) {
			xs.add(cell.x());
			zs.add(cell.z());
		}

		if (cells.size() == 3) {
			return xs.size() == 2 && zs.size() == 2 ? "L-shape" : "1x3";
		}

		if (cells.size() == 4) {
			return xs.size() == 2 && zs.size() == 2 ? "2x2" : "1x4";
		}

		return "undefined";
	}

	/**
	 * Which rotations can possibly apply: squares need all four checked, a
	 * rectangle two, and an L-shape betrays its single orientation by which
	 * corner of its bounding square is missing.
	 */
	public static List<String> possibleDirections(String size, List<Cell> cells) {
		List<String> directions = new ArrayList<>();

		if (size.equals("1x1") || size.equals("2x2")) {
			directions.add("NW");
			directions.add("NE");
			directions.add("SE");
			directions.add("SW");
			return directions;
		}

		TreeSet<Integer> xs = new TreeSet<>();
		TreeSet<Integer> zs = new TreeSet<>();

		for (Cell cell : cells) {
			xs.add(cell.x());
			zs.add(cell.z());
		}

		if (size.equals("L-shape")) {
			List<Integer> x = new ArrayList<>(xs);
			List<Integer> z = new ArrayList<>(zs);

			if (!cells.contains(new Cell(x.get(0), z.get(0)))) {
				directions.add("SW");
			} else if (!cells.contains(new Cell(x.get(0), z.get(1)))) {
				directions.add("SE");
			} else if (!cells.contains(new Cell(x.get(1), z.get(0)))) {
				directions.add("NW");
			} else if (!cells.contains(new Cell(x.get(1), z.get(1)))) {
				directions.add("NE");
			}
		} else if (size.startsWith("1x")) {
			if (xs.size() >= 2 && zs.size() == 1) {
				directions.add("NW");
				directions.add("SE");
			} else if (xs.size() == 1 && zs.size() >= 2) {
				directions.add("NE");
				directions.add("SW");
			}
		}

		return directions;
	}

	/**
	 * The world x/z of the room's extreme corner in a direction - for L-shapes
	 * the corner of the bounding square, present or not, exactly as recorded.
	 */
	public static Cell cornerFor(String direction, List<Cell> cells) {
		TreeSet<Integer> xs = new TreeSet<>();
		TreeSet<Integer> zs = new TreeSet<>();

		for (Cell cell : cells) {
			xs.add(cell.x());
			zs.add(cell.z());
		}

		return switch (direction) {
			case "NW" -> new Cell(xs.first(), zs.first());
			case "NE" -> new Cell(xs.last() + 30, zs.first());
			case "SE" -> new Cell(xs.last() + 30, zs.last() + 30);
			case "SW" -> new Cell(xs.first(), zs.last() + 30);
			default -> null;
		};
	}

	/** World position to recorded-room position; y passes through unchanged. */
	public static int[] actualToRelative(int x, int y, int z,
			String direction, Cell corner) {
		return switch (direction) {
			case "NW" -> new int[]{x - corner.x(), y, z - corner.z()};
			case "NE" -> new int[]{z - corner.z(), y, -(x - corner.x())};
			case "SE" -> new int[]{-(x - corner.x()), y, -(z - corner.z())};
			case "SW" -> new int[]{-(z - corner.z()), y, x - corner.x()};
			default -> null;
		};
	}

	/** Recorded-room position to world position; y passes through unchanged. */
	public static int[] relativeToActual(int x, int y, int z,
			String direction, Cell corner) {
		return switch (direction) {
			case "NW" -> new int[]{x + corner.x(), y, z + corner.z()};
			case "NE" -> new int[]{-(z - corner.x()), y, x + corner.z()};
			case "SE" -> new int[]{-(x - corner.x()), y, -(z - corner.z())};
			case "SW" -> new int[]{z + corner.x(), y, -(x - corner.z())};
			default -> null;
		};
	}

	/**
	 * Whether a block sits in the doorway band - the centre strip at the cell
	 * edges, y 66-73 - which belongs to corridors, not to any room's identity.
	 */
	public static boolean isDoorway(int x, int y, int z) {
		if (y < 66 || y > 73) {
			return false;
		}

		int relX = Math.floorMod(x - 8, 32);
		int relZ = Math.floorMod(z - 8, 32);

		if (relX >= 13 && relX <= 17 && (relZ <= 2 || relZ >= 28)) {
			return true;
		}

		return relZ >= 13 && relZ <= 17 && (relX <= 2 || relX >= 28);
	}

	/**
	 * Packs a relative position and legacy block id into the 8-byte key the
	 * .skeleton data stores - four shorts: x, y, z, id.
	 */
	public static long packBlock(int relX, int relY, int relZ, int legacyId) {
		short a = (short) relX;
		short b = (short) relY;
		short c = (short) relZ;
		short d = (short) legacyId;
		return ((long) ((a << 16) | (b & 0xFFFF)) << 32)
				| (((c << 16) | (d & 0xFFFF)) & 0xFFFFFFFFL);
	}
}
