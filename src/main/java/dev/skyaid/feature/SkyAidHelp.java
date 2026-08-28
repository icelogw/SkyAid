package dev.skyaid.feature;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Prints the command list, narrowed to whatever was actually typed.
 *
 * <p>Wired to every incomplete branch of {@code /skyaid}. Without it, Brigadier
 * cannot match a partial command, so Minecraft forwards it to the server and
 * Hypixel answers "Unknown command" - which reads as the mod being broken rather
 * than the command being half-typed.
 *
 * <p>Each branch shows only its own commands: typing {@code /skyaid key} is a
 * question about keys, and answering it with the whole command list buries the
 * three lines that were being asked for.
 */
public final class SkyAidHelp {
	private record Entry(String command, String description, boolean runnable) {
	}

	/** Ordered as they would be discovered, general to specific. */
	private static final Entry[] COMMANDS = {
			new Entry("/skyaid help", "show this list", true),
			new Entry("/skyaid key add", "set your Hypixel API key", true),
			new Entry("/skyaid key status", "check the stored key", true),
			new Entry("/skyaid key clear", "forget the stored key", true),
			new Entry("/skyaid stats <player>", "look up a player's Hypixel stats", false),
			new Entry("/skyaid auctions", "your auctions: top bid, last bid, time left", true),
			new Entry("/skyaid highlight", "list your custom highlight words", true),
			new Entry("/skyaid highlight add <word>", "highlight a word like your name", false),
			new Entry("/skyaid highlight remove <word>", "stop highlighting a word", false),
			new Entry("/skyaid session", "show this session's coin and bit gains", true),
			new Entry("/skyaid session reset", "start the session counters over", true),
			new Entry("/skyaid price <item>", "bazaar or auction price of an item", false),
			new Entry("/skyaid flips", "best bazaar flip margins right now", true),
			new Entry("/skyaid tutorial", "money-making guide with live estimates", true),
			new Entry("/skyaid craft <item>", "is the enchanted craft worth it", false),
			new Entry("/skyaid jacob", "upcoming Jacob's contests; watch crops", true),
			new Entry("/skyaid visitors", "what Garden visitors have cost you", true),
			new Entry("/skyaid report", "report a bug - Discord and GitHub links", true),
			new Entry("/skyaid mouselock", "open the preset group menu", true),
			new Entry("/skyaid mouselock on|off", "master switch for the hold-keys", false),
			new Entry("/skyaid mouselock <yaw> <pitch>", "set an exact angle, then lock", false),
			new Entry("/skyaid waypoint", "list your beacon markers", true),
			new Entry("/skyaid waypoint add <name>", "drop a beacon marker where you stand", false),
			new Entry("/skyaid waypoint remove <name>", "remove one marker", false),
			new Entry("/skyaid waypoint clear", "remove every marker", true),
			new Entry("/skyaid dump", "write the sidebar to a file for debugging", true)
	};

	private SkyAidHelp() {
	}

	/**
	 * Registers {@code /skyaid help}. The bare {@code /skyaid} branch prints the
	 * same list, but only an explicit help command is guessable without already
	 * knowing that.
	 */
	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("help")
								.executes(context -> {
									showAll();
									return 1;
								}))));
	}

	/** Every command the mod provides. */
	public static void showAll() {
		show("/skyaid");
	}

	/**
	 * Only the commands under {@code prefix}, e.g. "/skyaid key" for the three key
	 * commands. Falls back to everything if the prefix matches nothing, so a branch
	 * added later without a matching entry still prints something useful.
	 */
	public static void show(String prefix) {
		Minecraft client = Minecraft.getInstance();

		if (client.gui == null) {
			return;
		}

		var chat = client.gui.hud.getChat();
		List<Entry> chosen = Arrays.stream(COMMANDS)
				.filter(entry -> entry.command().startsWith(prefix))
				.toList();

		if (chosen.isEmpty()) {
			chosen = List.of(COMMANDS);
		}

		chat.addClientSystemMessage(Component.empty());
		chat.addClientSystemMessage(
				Component.literal(heading(prefix)).withStyle(ChatFormatting.AQUA));

		for (Entry entry : chosen) {
			chat.addClientSystemMessage(line(entry));
		}

		chat.addClientSystemMessage(Component.empty());
	}

	private static String heading(String prefix) {
		String subcommand = prefix.replaceFirst("^/skyaid[ ]?", "").trim();

		return subcommand.isEmpty()
				? "SkyAid commands"
				: "SkyAid " + subcommand + " commands";
	}

	/**
	 * Commands that take no arguments are clickable; the ones needing a value are
	 * not, since running them as-is would just fail again.
	 */
	private static Component line(Entry entry) {
		Component command = Component.literal("  " + entry.command())
				.withStyle(style -> entry.runnable()
						? style.withColor(ChatFormatting.YELLOW)
								.withClickEvent(new ClickEvent.RunCommand(entry.command()))
						: style.withColor(ChatFormatting.YELLOW));

		return command.copy()
				.append(Component.literal("  " + entry.description())
						.withStyle(ChatFormatting.GRAY));
	}
}
