package dev.skyaid.dungeon.solvers;

import dev.skyaid.dungeon.core.DungeonTracker;
import dev.skyaid.dungeon.core.MarkerRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * Tic-tac-toe against the dungeon: the board is nine item frames on an iron
 * wall, each placed move a filled map whose centre pixel is red (X, the
 * dungeon) or blue (O, the player). Reads the board off the maps, runs
 * minimax for O, and marks the square to click. Geometry and colour
 * constants per Skytils' solver: board rows at world Y 70-72, map colour
 * index 8256, X=114, O=33.
 */
final class TicTacToeSolver implements PuzzleSolver {
	private static final int MAP_COLOR_INDEX = 8256;
	private static final int COLOR_X = 114;
	private static final int COLOR_O = 33;

	private static final int ROW_TOP_Y = 72;

	private static final int SCAN_INTERVAL_TICKS = 20;

	private int tickCounter;
	private BlockPos bestMove;

	private record Placed(int row, int column, BlockPos basePos,
			Direction facing, boolean isX) {
	}

	@Override
	public boolean handles(String roomName) {
		return roomName.equals("Tic-Tac-Toe-1");
	}

	@Override
	public void tick(Minecraft client, DungeonTracker.Room room) {
		if (room == null) {
			return;
		}

		if (++tickCounter >= SCAN_INTERVAL_TICKS) {
			tickCounter = 0;
			bestMove = solve(client);
		}

		if (bestMove != null) {
			MarkerRenderer.action(bestMove, "Place");
		}
	}

	@Override
	public void reset() {
		bestMove = null;
		tickCounter = 0;
	}

	private static BlockPos solve(Minecraft client) {
		List<Placed> placed = new ArrayList<>();

		for (Entity entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof ItemFrame frame) || frame.getRotation() != 0) {
				continue;
			}

			BlockPos base = frame.blockPosition().below();

			if (base.getY() < ROW_TOP_Y - 2 || base.getY() > ROW_TOP_Y) {
				continue;
			}

			var stack = frame.getItem();
			var mapId = stack.get(DataComponents.MAP_ID);

			if (mapId == null) {
				continue;
			}

			MapItemSavedData map = client.level.getMapData(mapId);

			if (map == null) {
				continue;
			}

			int colour = map.colors[MAP_COLOR_INDEX] & 0xFF;

			if (colour != COLOR_X && colour != COLOR_O) {
				continue;
			}

			Direction facing = frame.getDirection();
			BlockPos behind = base.relative(facing.getOpposite());

			if (client.level.getBlockState(behind).getBlock() != Blocks.IRON_BLOCK) {
				continue;
			}

			// Column from the iron edge test: a missing iron neighbour marks
			// which end of the row this frame hangs at.
			int column;

			if (client.level.getBlockState(behind.relative(
					facing.getCounterClockWise())).getBlock() != Blocks.IRON_BLOCK) {
				column = 2;
			} else if (client.level.getBlockState(behind.relative(
					facing.getClockWise())).getBlock() != Blocks.IRON_BLOCK) {
				column = 0;
			} else {
				column = 1;
			}

			placed.add(new Placed(ROW_TOP_Y - base.getY(), column,
					base.immutable(), facing, colour == COLOR_X));
		}

		// The dungeon plays X and moves first; O moves when counts are odd.
		if (placed.isEmpty() || placed.size() % 2 == 0 || placed.size() >= 9) {
			return null;
		}

		int[] board = new int[9];

		for (Placed move : placed) {
			board[move.row() * 3 + move.column()] = move.isX() ? 1 : 2;
		}

		int best = bestMoveForO(board);

		if (best < 0) {
			return null;
		}

		// Any known frame anchors the wall's coordinate system: columns run
		// clockwise-to-counterclockwise per the edge test above, rows run down.
		Placed reference = placed.get(0);
		Direction columnAxis = reference.facing().getCounterClockWise();
		int rowDelta = (best / 3) - reference.row();
		int columnDelta = (best % 3) - reference.column();

		return reference.basePos()
				.below(rowDelta)
				.relative(columnAxis, columnDelta);
	}

	/** Plain full-depth minimax for O on a 3x3 board; tiny by construction. */
	private static int bestMoveForO(int[] board) {
		int bestScore = Integer.MIN_VALUE;
		int bestCell = -1;

		for (int cell = 0; cell < 9; cell++) {
			if (board[cell] != 0) {
				continue;
			}

			board[cell] = 2;
			int score = minimax(board, false);
			board[cell] = 0;

			if (score > bestScore) {
				bestScore = score;
				bestCell = cell;
			}
		}

		return bestCell;
	}

	private static int minimax(int[] board, boolean oToMove) {
		int winner = winnerOf(board);

		if (winner == 2) {
			return 1;
		}

		if (winner == 1) {
			return -1;
		}

		boolean full = true;
		int best = oToMove ? Integer.MIN_VALUE : Integer.MAX_VALUE;

		for (int cell = 0; cell < 9; cell++) {
			if (board[cell] != 0) {
				continue;
			}

			full = false;
			board[cell] = oToMove ? 2 : 1;
			int score = minimax(board, !oToMove);
			board[cell] = 0;
			best = oToMove ? Math.max(best, score) : Math.min(best, score);
		}

		return full ? 0 : best;
	}

	private static final int[][] LINES = {
			{0, 1, 2}, {3, 4, 5}, {6, 7, 8},
			{0, 3, 6}, {1, 4, 7}, {2, 5, 8},
			{0, 4, 8}, {2, 4, 6}};

	private static int winnerOf(int[] board) {
		for (int[] line : LINES) {
			if (board[line[0]] != 0 && board[line[0]] == board[line[1]]
					&& board[line[1]] == board[line[2]]) {
				return board[line[0]];
			}
		}

		return 0;
	}
}
