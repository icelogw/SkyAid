package dev.skyaid.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.DisplaySlot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pulls the sidebar out of the client scoreboard as plain strings.
 *
 * <p>Thin on purpose: this is the only Minecraft-facing part of sidebar handling,
 * and everything it produces is handed straight to
 * {@code dev.skyaid.parse.ScoreboardParser}, which is testable in isolation.
 */
public final class ScoreboardReader {
	private ScoreboardReader() {
	}

	/** The sidebar objective's title, or an empty string if there is no sidebar. */
	public static String title() {
		Objective objective = sidebarObjective();
		return objective == null ? "" : objective.getDisplayName().getString();
	}

	/**
	 * The sidebar rows, top to bottom.
	 *
	 * <p>Each row's visible text is the score holder's name wrapped in its team's
	 * prefix and suffix - that is where Hypixel puts the actual content, with the
	 * holder name itself often being a throwaway unique string.
	 */
	public static List<String> lines() {
		Objective objective = sidebarObjective();

		if (objective == null) {
			return List.of();
		}

		Scoreboard scoreboard = objective.getScoreboard();
		List<PlayerScoreEntry> entries = new ArrayList<>(scoreboard.listPlayerScores(objective));

		// Vanilla renders the sidebar highest score first.
		entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());

		List<String> out = new ArrayList<>(entries.size());

		for (PlayerScoreEntry entry : entries) {
			if (entry.isHidden()) {
				continue;
			}

			PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
			Component line = PlayerTeam.formatNameForTeam(team, entry.ownerName());
			out.add(line.getString());
		}

		return out;
	}

	private static Objective sidebarObjective() {
		ClientLevel level = Minecraft.getInstance().level;

		if (level == null) {
			return null;
		}

		return level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
	}
}
