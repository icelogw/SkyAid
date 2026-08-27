package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.SessionTracker;
import dev.skyaid.parse.Numbers;
import dev.skyaid.parse.SessionStats;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.OptionalLong;

/**
 * {@code /skyaid session} prints the session's gains in chat, with the exact
 * figures the HUD abbreviates; {@code /skyaid session reset} starts over.
 *
 * <p>The reset exists because the tracker cannot tell a profile switch from an
 * earning - the purse just moves - so the player needs a way to say "count from
 * here". It resets on its own only when the game closes, never on disconnect.
 */
public final class SessionCommand {
	private SessionCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("session")
								.executes(context -> {
									report();
									return 1;
								})
								.then(ClientCommands.literal("reset")
										.executes(context -> {
											SessionTracker.reset();
											say(Component.empty());
											say(Component.literal("Session counters reset.")
													.withStyle(ChatFormatting.GREEN));
											say(Component.empty());
											return 1;
										})))));
	}

	private static void report() {
		SessionStats.Snapshot session = SessionTracker.snapshot();

		// A blank line either side, so the block does not sit jammed against
		// ordinary chat - the same breathing room the help list gets.
		say(Component.empty());

		if (!session.started()) {
			say(Component.literal("No session yet - it starts counting in Skyblock.")
					.withStyle(ChatFormatting.GRAY));
			say(Component.empty());
			return;
		}

		say(Component.literal("Session: ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(session.formattedDuration() + " in Skyblock")
						.withStyle(ChatFormatting.WHITE)));

		// Says which window the rate covers, since the slider changes what it means.
		int window = ConfigManager.get().skyblockHud.coinsPerHourWindowMinutes;
		String rateLabel = window == 0 ? "Coins/h (session)" : "Coins/h (last " + window + "m)";

		say(line("Coins", session.coinsGained(), ChatFormatting.GOLD,
				"purse not seen yet"));
		say(line(rateLabel, session.coinsPerHour(), ChatFormatting.GOLD,
				"needs a minute of play"));
		say(line("Bits", session.bitsGained(), ChatFormatting.AQUA,
				"only visible in the hub"));
		say(Component.empty());
	}

	/** "  Coins: +34,500", or the reason there is no figure, dimmed. */
	private static Component line(
			String label, OptionalLong value, ChatFormatting colour, String whyAbsent) {
		Component name = Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY);

		if (value.isEmpty()) {
			return name.copy().append(
					Component.literal("(" + whyAbsent + ")").withStyle(ChatFormatting.DARK_GRAY));
		}

		long gained = value.getAsLong();
		String figure = (gained < 0 ? "" : "+") + Numbers.group(gained);

		return name.copy().append(Component.literal(figure).withStyle(colour));
	}

	private static void say(Component message) {
		Minecraft client = Minecraft.getInstance();

		if (client.gui != null) {
			client.gui.hud.getChat().addClientSystemMessage(message);
		}
	}
}
