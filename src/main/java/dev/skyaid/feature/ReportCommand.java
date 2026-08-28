package dev.skyaid.feature;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;

/**
 * {@code /skyaid report}: where to send bugs - a clickable Discord invite
 * and the GitHub issues page. A dead-simple road from "this looks wrong"
 * to a place the author actually reads.
 */
public final class ReportCommand {
	private static final String DISCORD_INVITE = "https://discord.gg/QvE3wU8zGT";
	private static final String ISSUES_URL =
			"https://github.com/icelogw/SkyAid/issues";

	private ReportCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("report").executes(context -> {
							say();
							return 1;
						}))));
	}

	private static void say() {
		var client = Minecraft.getInstance();

		if (client.gui == null) {
			return;
		}

		var chat = client.gui.hud.getChat();
		chat.addClientSystemMessage(Component.empty());
		chat.addClientSystemMessage(Component.literal(
						"Found a bug, or want a feature? Come say so:")
				.withStyle(ChatFormatting.AQUA));
		chat.addClientSystemMessage(Component.literal("  ")
				.append(link("[SkyAid Discord]", DISCORD_INVITE,
						ChatFormatting.LIGHT_PURPLE))
				.append(Component.literal("  "))
				.append(link("[GitHub Issues]", ISSUES_URL, ChatFormatting.AQUA)));
		chat.addClientSystemMessage(Component.literal(
						"  A screenshot plus /skyaid dump makes any bug fixable.")
				.withStyle(ChatFormatting.DARK_GRAY));
		chat.addClientSystemMessage(Component.empty());
	}

	private static Component link(String label, String url, ChatFormatting colour) {
		return Component.literal(label).withStyle(style -> style
				.withColor(colour)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
				.withHoverEvent(new HoverEvent.ShowText(
						Component.literal(url).withStyle(ChatFormatting.GRAY))));
	}
}
