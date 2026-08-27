package dev.skyaid.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The tab list as plain lines, in display order. In dungeons Hypixel packs
 * the whole run state into fake tab entries - puzzle statuses, secrets
 * percentage, deaths, milestones - which makes this the most information-dense
 * surface the client gets, and the one every mature dungeon mod reads.
 */
public final class TabListReader {
	private TabListReader() {
	}

	/** Every tab entry's display text, ordered as the list shows them. */
	public static List<String> lines() {
		var connection = Minecraft.getInstance().getConnection();

		if (connection == null) {
			return List.of();
		}

		List<PlayerInfo> entries = new ArrayList<>(connection.getListedOnlinePlayers());
		entries.sort(Comparator
				.comparingInt(PlayerInfo::getTabListOrder)
				.thenComparing(info -> info.getProfile().name()));

		List<String> lines = new ArrayList<>(entries.size());

		for (PlayerInfo info : entries) {
			lines.add(info.getTabListDisplayName() != null
					? info.getTabListDisplayName().getString()
					: info.getProfile().name());
		}

		return lines;
	}
}
