package dev.skyaid.feature;

import dev.skyaid.SkyAidClient;
import dev.skyaid.api.ApiStatus;
import dev.skyaid.api.HypixelApiClient;
import dev.skyaid.config.ApiKeyScreen;
import dev.skyaid.config.ConfigManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Key management commands:
 * <ul>
 *   <li>{@code /skyaid key add} - opens the popup to enter one</li>
 *   <li>{@code /skyaid key status} - checks the stored key against Hypixel</li>
 *   <li>{@code /skyaid key clear} - forgets it</li>
 * </ul>
 *
 * <p>None of these takes the key as an argument, and that is deliberate. Client
 * commands do not reach the server when typed correctly, but anything typed into
 * the chat box lands in the client's recent-chat history, and a command mistyped
 * badly enough not to parse gets sent as public chat - which would leak the key
 * to the whole lobby. Entering a key only ever happens in the popup, which
 * cannot fail that way.
 *
 * <p>Nothing here ever prints the key, not even partially.
 */
public final class ApiKeyCommand {
	private ApiKeyCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						// Bare /skyaid prints the command list rather than falling through
						// to the server, which answers "Unknown command".
						.executes(context -> {
							SkyAidHelp.showAll();
							return 1;
						})
						.then(ClientCommands.literal("key")
								// Shows only the key commands - someone typing this is asking
								// about keys, not for the whole command list.
								.executes(context -> {
									SkyAidHelp.show("/skyaid key");
									return 1;
								})
								.then(ClientCommands.literal("add")
										.executes(context -> {
											openScreen();
											return 1;
										}))
								.then(ClientCommands.literal("status")
										.executes(context -> {
											status();
											return 1;
										}))
								.then(ClientCommands.literal("clear")
										.executes(context -> {
											clear();
											return 1;
										})))));
	}

	/**
	 * Opens the popup on the next tick. The command runs while the chat screen is
	 * still closing, so setting a screen immediately would be undone by that close.
	 */
	private static void openScreen() {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> client.setScreenAndShow(new ApiKeyScreen(null)));
	}

	private static void status() {
		// exceptionally() before thenAccept so a failure inside the check still
		// produces a line. Without it a thrown exception is swallowed into the
		// future and the command appears to do nothing at all, which is worse than
		// any error message.
		HypixelApiClient.checkKey()
				.exceptionally(error -> {
					SkyAidClient.LOGGER.warn("Key check failed ({})",
							error.getClass().getSimpleName());
					return HypixelApiClient.KeyCheck.UNREACHABLE;
				})
				.thenAccept(result ->
						Minecraft.getInstance().execute(() -> announce(ApiStatus.of(result))));
	}

	/**
	 * Asks before forgetting the key. Confirming through a screen rather than a
	 * second command keeps it consistent with the popup's Clear button, and means
	 * a mistyped command can never destroy the key on its own.
	 */
	private static void clear() {
		if (!HypixelApiClient.hasApiKey()) {
			announce(Component.literal("There is no key to clear.")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		Minecraft client = Minecraft.getInstance();

		client.execute(() -> client.setScreenAndShow(new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						ConfigManager.get().hypixelApiKey = "";
						ConfigManager.save();
					}

					client.setScreenAndShow(null);

					announce(confirmed
							? Component.literal("API key cleared.").withStyle(ChatFormatting.YELLOW)
							: Component.literal("Kept your API key.").withStyle(ChatFormatting.GRAY));
				},
				Component.literal("Clear your Hypixel API key?"),
				Component.literal(
						"SkyAid will forget it. You can paste it again, or make a new one "
								+ "at developer.hypixel.net."),
				Component.literal("Clear key"),
				CommonComponents.GUI_CANCEL)));
	}

	/**
	 * Prints with a blank line either side, so it stands out from lobby chatter.
	 *
	 * <p>Writes to the chat component directly rather than through the command
	 * source. These results arrive after the network call returns, by which point
	 * the command that produced the source has long finished - feeding a stale
	 * source produced no output at all. The chat component has no such lifetime.
	 */
	private static void announce(Component message) {
		Minecraft client = Minecraft.getInstance();

		if (client.gui == null) {
			return;
		}

		var chat = client.gui.hud.getChat();
		chat.addClientSystemMessage(Component.empty());
		chat.addClientSystemMessage(message);
		chat.addClientSystemMessage(Component.empty());
	}
}
