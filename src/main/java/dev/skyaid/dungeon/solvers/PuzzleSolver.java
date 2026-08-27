package dev.skyaid.dungeon.solvers;

import dev.skyaid.dungeon.core.DungeonTracker;
import net.minecraft.client.Minecraft;

/**
 * One dungeon puzzle solver. Activation is by database room name - the room
 * identification in {@link DungeonTracker} already knows every puzzle room
 * ("Water Puzzle", "Creeper-Room", "Blaze-Room-1-High"...), so no solver
 * carries its own fragile detection any more.
 *
 * <p>Solvers point, players act: markers go through
 * {@link dev.skyaid.dungeon.core.MarkerRenderer#action}, and nothing is ever
 * clicked, moved or sent on the player's behalf.
 */
public interface PuzzleSolver {
	/** Whether this solver handles the given database room name. */
	boolean handles(String roomName);

	/**
	 * A self-anchored solver carries its own unmistakable in-world trigger
	 * (the creeper solver's charged creeper) and may tick before the room is
	 * identified - room identification takes seconds, and a solver that can
	 * safely skip the wait should.
	 */
	default boolean selfAnchored() {
		return false;
	}

	/**
	 * Called every client tick while the player is in a room this solver
	 * handles. {@code room} is null only in debug mode outside an identified
	 * room, for solvers that can safely self-anchor while testing offline.
	 */
	void tick(Minecraft client, DungeonTracker.Room room);

	/** Called whenever this solver's room is left; drop all state. */
	void reset();
}
