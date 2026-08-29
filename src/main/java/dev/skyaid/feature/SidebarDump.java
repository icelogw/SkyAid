package dev.skyaid.feature;

import dev.skyaid.SkyAidClient;
import dev.skyaid.core.BossBarReader;
import dev.skyaid.dungeon.core.SecretsBoard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import dev.skyaid.core.ScoreboardReader;
import dev.skyaid.core.SessionTracker;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.parse.ActionBarParser;
import dev.skyaid.parse.ActionBarState;
import dev.skyaid.parse.BossBars;
import dev.skyaid.parse.ScoreboardParser;
import dev.skyaid.parse.SessionStats;
import dev.skyaid.parse.SkyblockState;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Writes the raw sidebar and action bar to a file, with every invisible
 * character spelled out as a code point.
 *
 * <p>This exists because parsing bugs here are invisible by inspection. Hypixel
 * pads its lines with a section sign followed by an arbitrary letter, and one
 * landing inside a number silently truncated a purse from 7,884,267 to 78,842.
 * Nothing in the game shows you that; the only way to see it is to dump the
 * bytes. The same capture also revealed the location glyph to be a private-use
 * character rather than the emoji the parser had assumed.
 *
 * <p>The workflow it enables: run it once in game, hand the file over, and the
 * exact line becomes a fixture in the unit tests - so the parser can then be
 * fixed and verified entirely offline, with no need to be on Hypixel at all.
 *
 * <p>Reachable as {@code /skyaid dump} and from the Other tab of the settings.
 */
public final class SidebarDump {
	private SidebarDump() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("dump")
								.executes(context -> {
									dumpAndReport();
									return 1;
								}))));
	}

	/**
	 * Writes the dump and says where it went, in chat.
	 *
	 * <p>Deliberately not gated on being connected to Hypixel. It writes a local
	 * file and reveals nothing, and a dump showing an empty sidebar is itself
	 * useful - it distinguishes "the parser is wrong" from "there was nothing to
	 * parse", which is exactly the question worth answering when a readout is
	 * missing.
	 */
	public static void dumpAndReport() {
		Optional<Path> written = write();
		Minecraft client = Minecraft.getInstance();

		if (client.gui == null) {
			return;
		}

		var chat = client.gui.hud.getChat();
		chat.addClientSystemMessage(Component.empty());
		chat.addClientSystemMessage(written
				.map(path -> Component.literal("Dumped to " + path.getFileName())
						.withStyle(ChatFormatting.GREEN))
				.orElseGet(() -> Component.literal("Could not write the dump file.")
						.withStyle(ChatFormatting.RED)));
		chat.addClientSystemMessage(Component.empty());
	}

	/** @return where the dump was written, or empty if it could not be saved */
	public static Optional<Path> write() {
		Path path = FabricLoader.getInstance().getGameDir()
				.resolve(SkyAidClient.MOD_ID + "-sidebar-dump.txt");

		try {
			Files.writeString(path, report());
			return Optional.of(path);
		} catch (IOException e) {
			SkyAidClient.LOGGER.warn("Could not write the sidebar dump");
			return Optional.empty();
		}
	}

	private static String report() {
		String title = ScoreboardReader.title();
		List<String> lines = ScoreboardReader.lines();

		StringBuilder out = new StringBuilder();
		out.append("SkyAid sidebar dump\n");
		out.append("===================\n\n");
		out.append("TITLE: ").append(escape(title)).append('\n');
		out.append("\nLINES (").append(lines.size()).append("):\n");

		for (int i = 0; i < lines.size(); i++) {
			out.append(String.format("%2d: %s%n", i, escape(lines.get(i))));
		}

		// Boss bars carry the zone-quest objective ("Objective: Talk to ..."), so
		// a dump without them once hid exactly the line being hunted for.
		List<String> bars = BossBarReader.barNames();
		out.append("\nBOSS BARS (").append(bars.size()).append("):\n");

		for (String bar : bars) {
			out.append("  ").append(escape(bar)).append('\n');
		}

		// The action bar is the other thing the HUD reads, and it has the same
		// hazard: Hypixel draws its symbols from a private-use font range, so a
		// parser that assumed a particular emoji stopped matching silently.
		String actionBar = SkyblockTracker.rawActionBar();
		ActionBarState bar = ActionBarParser.parse(actionBar);

		out.append("\nACTION BAR: ").append(escape(actionBar)).append('\n');
		out.append("  health:  ").append(text(bar.health()))
				.append(" / ").append(text(bar.maxHealth())).append('\n');
		out.append("  defense: ").append(text(bar.defense())).append('\n');
		out.append("  mana:    ").append(text(bar.mana()))
				.append(" / ").append(text(bar.maxMana())).append('\n');

		// Include what the parser made of it, so a mismatch is obvious side by side.
		SkyblockState state = ScoreboardParser.parse(title, lines);

		// Mirror the tracker's composition: the objective rides in on a boss bar,
		// so a sidebar-only parse here once printed "(none)" while the HUD - which
		// gets the grafted state - was showing the quest.
		Optional<String> objective = BossBars.objective(bars);

		if (objective.isPresent()) {
			state = state.withObjective(objective.get());
		}
		out.append("\nPARSED:\n");
		out.append("  inSkyblock: ").append(state.inSkyblock()).append('\n');
		out.append("  location:   ").append(state.location().orElse("(none)")).append('\n');
		out.append("  serverId:   ").append(state.serverId().orElse("(none)")).append('\n');
		out.append("  purse:      ").append(text(state.purse())).append('\n');
		out.append("  bits:       ").append(text(state.bits())).append('\n');
		out.append("  date:       ").append(state.date().orElse("(none)")).append('\n');
		out.append("  time:       ").append(state.time().orElse("(none)")).append('\n');
		out.append("  slayer:     ").append(state.slayerQuest().orElse("(none)")).append('\n');
		out.append("  slayerStat: ").append(state.slayerStatus().orElse("(none)")).append('\n');
		out.append("  objective:  ").append(state.objective().orElse("(none)")).append('\n');
		out.append("  objStatus:  ").append(state.objectiveStatus().orElse("(none)")).append('\n');

		// The lines nothing claimed, exactly as the HUD's "Other lines" gets them.
		// A recognisable value sitting here means a matcher failed to claim it.
		out.append("  extras (").append(state.extraLines().size()).append("):\n");

		for (String extra : state.extraLines()) {
			out.append("    ").append(escape(extra)).append('\n');
		}

		SessionStats.Snapshot session = SessionTracker.snapshot();
		out.append("\nSESSION:\n");
		out.append("  active:     ").append(session.formattedDuration()).append('\n');
		out.append("  coins:      ").append(text(session.coinsGained())).append('\n');
		out.append("  coins/h:    ").append(text(session.coinsPerHour())).append('\n');
		out.append("  bits:       ").append(text(session.bitsGained())).append('\n');

		// The dungeon run state lives in fake tab entries; raw lines with
		// escapes are what future parsers get built and corrected from.
		List<String> tab = dev.skyaid.core.TabListReader.lines();
		out.append("\nTAB LIST (").append(tab.size()).append("):\n");

		for (int i = 0; i < Math.min(tab.size(), 60); i++) {
			out.append(String.format("%2d: %s%n", i, escape(tab.get(i))));
		}

		appendHeads(out);
		dev.skyaid.core.EventLog.dumpInto(out);
		SignSearchAssist.dumpInto(out);
		FairySouls.dumpInto(out);
		PestHighlight.dumpInto(out);
		VisitorCost.dumpInto(out);
		PriceTooltips.dumpInto(out, Minecraft.getInstance().player == null
				? null : Minecraft.getInstance().player.getMainHandItem());
		MuseumTracker.dumpInto(out);
		JacobContests.dumpInto(out);
		BazaarOrders.dumpInto(out);
		dev.skyaid.dungeon.core.DungeonTracker.dumpInto(out);
		dev.skyaid.dungeon.core.SecretsBoard.dumpInto(out);
		dev.skyaid.dungeon.solvers.CreeperBeamsSolver.dumpInto(out);
		dev.skyaid.dungeon.solvers.WaterboardSolver.dumpInto(out);
		dev.skyaid.dungeon.terminals.TerminalSolvers.dumpInto(out);
		dev.skyaid.dungeon.solvers.IcePathSolver.dumpInto(out);

		return out.toString();
	}

	/**
	 * Player heads near the player, with their skin-texture hashes. Hypixel
	 * builds decorations AND essence-skull secrets from custom-textured player
	 * heads, so texture is the only way to tell them apart - standing next to
	 * a real secret and dumping is how a new texture gets identified for the
	 * secret markers.
	 */
	private static void appendHeads(StringBuilder out) {
		Minecraft client = Minecraft.getInstance();
		out.append("\nPLAYER HEADS (within 8 blocks):\n");

		if (client.level == null || client.player == null) {
			out.append("  (not in a world)\n");
			return;
		}

		BlockPos centre = client.player.blockPosition();
		int found = 0;

		for (BlockPos pos : BlockPos.betweenClosed(
				centre.offset(-8, -8, -8), centre.offset(8, 8, 8))) {
			Block block = client.level.getBlockState(pos).getBlock();

			if (block != Blocks.PLAYER_HEAD && block != Blocks.PLAYER_WALL_HEAD) {
				continue;
			}

			if (++found > 24) {
				out.append("  (more heads beyond this - stand closer to the one that matters)\n");
				break;
			}

			out.append("  ").append(pos.getX()).append(' ').append(pos.getY())
					.append(' ').append(pos.getZ()).append("  ")
					.append(SecretsBoard.skinHash(client.level, pos)
							.orElse("(no texture)"))
					.append('\n');
		}

		if (found == 0) {
			out.append("  (none)\n");
		}
	}

	private static String text(OptionalLong value) {
		return value.isPresent() ? Long.toString(value.getAsLong()) : "(none)";
	}

	/**
	 * Renders a line so nothing can hide: printable characters as themselves, and
	 * everything else - section signs, zero-width padding, private-use glyphs - as
	 * U+XXXX.
	 */
	private static String escape(String line) {
		StringBuilder out = new StringBuilder();

		line.codePoints().forEach(codePoint -> {
			boolean printable = codePoint >= 0x20 && codePoint < 0x7F;

			if (printable) {
				out.append((char) codePoint);
			} else {
				out.append(String.format("<U+%04X>", codePoint));
			}
		});

		return out.toString();
	}
}
