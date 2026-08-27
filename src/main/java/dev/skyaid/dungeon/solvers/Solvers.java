package dev.skyaid.dungeon.solvers;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.dungeon.core.DungeonTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.List;

/**
 * Runs exactly the puzzle solver whose room the player is standing in - room
 * identity comes from {@link DungeonTracker}, so activation is data, not
 * guesswork - and resets every other solver.
 */
public final class Solvers {
	private static final List<PuzzleSolver> ALL = List.of(
			new BlazeSolver(), new CreeperBeamsSolver(), new WaterboardSolver(),
			new TriviaSolver(), new ThreeWeirdosSolver(),
			new TicTacToeSolver(), new TeleportMazeSolver(), new IcePathSolver());

	private Solvers() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ConfigManager.get().enabled || !ConfigManager.get().puzzleSolvers) {
				return;
			}

			if (client.level == null || client.player == null) {
				resetAll();
				return;
			}

			boolean debug = ConfigManager.get().debug;

			if (!SkyblockTracker.state().inCatacombs() && !debug) {
				resetAll();
				return;
			}

			DungeonTracker.Room room = DungeonTracker.currentRoom().orElse(null);

			for (PuzzleSolver solver : ALL) {
				boolean named = room != null && solver.handles(room.name());

				// Self-anchored solvers skip the identification wait; debug
				// mode ticks everything since no database rooms exist offline.
				if (named || solver.selfAnchored() || (room == null && debug)) {
					solver.tick(client, named ? room : null);
				} else {
					solver.reset();
				}
			}
		});
	}

	private static void resetAll() {
		for (PuzzleSolver solver : ALL) {
			solver.reset();
		}
	}
}
