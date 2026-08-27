package dev.skyaid.parse;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A snapshot of what the Skyblock sidebar was showing.
 *
 * <p>Every field is optional because Hypixel omits lines depending on where the
 * player is: bits are hidden outside the hub, the purse line is replaced by a
 * piggy-bank line in some contexts, and the slayer block only appears during a
 * quest. A missing field means "the scoreboard did not show it", never "it is
 * zero" - the HUD must be able to tell those apart so it can hide a readout
 * rather than render a misleading 0.
 *
 * @param inSkyblock   whether the sidebar identified this as a Skyblock server
 * @param location     island/zone name, e.g. "Village"
 * @param serverId     Hypixel instance id, e.g. "m4A"
 * @param purse        coins in the purse (or piggy bank)
 * @param bits         unspent bits
 * @param date         in-game date, e.g. "Late Spring 21st"
 * @param time         in-game clock, e.g. "9:40am"
 * @param slayerQuest  active slayer quest, e.g. "Revenant Horror II"
 * @param slayerStatus its progress line, e.g. "Boss slain!"
 * @param objective       current quest or objective, e.g. "Talk to the Trapper"
 * @param objectiveStatus its progress line
 * @param extraLines   sidebar lines no matcher claimed, in display order
 */
public record SkyblockState(
		boolean inSkyblock,
		Optional<String> location,
		Optional<String> serverId,
		OptionalLong purse,
		OptionalLong bits,
		Optional<String> date,
		Optional<String> time,
		Optional<String> slayerQuest,
		Optional<String> slayerStatus,
		Optional<String> objective,
		Optional<String> objectiveStatus,
		List<String> extraLines) {

	/**
	 * Whether the player is inside the Catacombs (any floor). The location reads
	 * "The Catacombs (F3)" there - verified against a real capture. Drives the
	 * dungeon HUD preset and the dungeon map.
	 */
	public boolean inCatacombs() {
		return location().map(name -> name.startsWith("The Catacombs")).orElse(false);
	}

	/**
	 * The dungeon floor tag from the location - "E" for entrance, "F1".."F7",
	 * "M1".."M7" for master mode - or empty outside the Catacombs. "The
	 * Catacombs (F2)" was verified against a real capture; the parenthetical
	 * is taken as-is rather than assumed to be any particular set of floors.
	 */
	public java.util.Optional<String> dungeonFloor() {
		if (!inCatacombs()) {
			return java.util.Optional.empty();
		}

		return location().flatMap(name -> {
			int open = name.lastIndexOf('(');
			int close = name.lastIndexOf(')');

			if (open < 0 || close <= open + 1) {
				return java.util.Optional.empty();
			}

			return java.util.Optional.of(name.substring(open + 1, close));
		});
	}

	/**
	 * This state with the objective replaced. The objective arrives as a boss
	 * bar rather than a sidebar line, so the tracker grafts it onto the parsed
	 * sidebar after the fact.
	 */
	public SkyblockState withObjective(String quest) {
		return new SkyblockState(inSkyblock, location, serverId, purse, bits,
				date, time, slayerQuest, slayerStatus,
				Optional.of(quest), objectiveStatus, extraLines);
	}

	/** State used when the player is not in Skyblock, or the sidebar is unreadable. */
	public static final SkyblockState EMPTY = new SkyblockState(
			false, Optional.empty(), Optional.empty(),
			OptionalLong.empty(), OptionalLong.empty(),
			Optional.empty(), Optional.empty(),
			Optional.empty(), Optional.empty(),
			Optional.empty(), Optional.empty(), List.of());
}
