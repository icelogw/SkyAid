package dev.skyaid.feature;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.skyaid.config.ConfigManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * {@code /skyaid highlight add|remove|list}: extra words treated like your own
 * name in chat - marked, and pinged when the mention sound is on.
 *
 * <p>For the things worth catching in a busy lobby: an item you are hunting,
 * your guild's name, "selling". Stored in the config, matched with the same
 * word-boundary rules as mentions so "art" never lights up "party".
 */
public final class HighlightCommand {
	/** Enough for anyone's watchlist; a cap only to keep the config sane. */
	private static final int MAX_WORDS = 20;
	private static final int MAX_WORD_LENGTH = 32;

	private HighlightCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("highlight")
								.executes(context -> {
									list(context.getSource());
									return 1;
								})
								.then(ClientCommands.literal("list")
										.executes(context -> {
											list(context.getSource());
											return 1;
										}))
								.then(ClientCommands.literal("add")
										.then(ClientCommands.argument(
														"word", StringArgumentType.greedyString())
												.executes(context -> {
													add(context.getSource(), StringArgumentType
															.getString(context, "word"));
													return 1;
												})))
								.then(ClientCommands.literal("remove")
										.then(ClientCommands.argument(
														"word", StringArgumentType.greedyString())
												.executes(context -> {
													remove(context.getSource(), StringArgumentType
															.getString(context, "word"));
													return 1;
												}))))));
	}

	private static void add(FabricClientCommandSource source, String word) {
		String trimmed = word.trim();
		List<String> words = ConfigManager.get().chat.highlightWords;

		if (trimmed.isEmpty() || trimmed.length() > MAX_WORD_LENGTH) {
			say(source, Component.literal(
							"Highlight words must be 1-" + MAX_WORD_LENGTH + " characters.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		if (contains(words, trimmed)) {
			say(source, Component.literal("\"" + trimmed + "\" is already highlighted.")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		if (words.size() >= MAX_WORDS) {
			say(source, Component.literal(
							"That is " + MAX_WORDS + " words already - remove one first.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		words.add(trimmed);
		ConfigManager.save();
		say(source, Component.literal("Now highlighting \"" + trimmed + "\".")
				.withStyle(ChatFormatting.GREEN));
	}

	private static void remove(FabricClientCommandSource source, String word) {
		String trimmed = word.trim();
		List<String> words = ConfigManager.get().chat.highlightWords;

		if (!words.removeIf(existing -> existing.equalsIgnoreCase(trimmed))) {
			say(source, Component.literal("\"" + trimmed + "\" was not on the list.")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		ConfigManager.save();
		say(source, Component.literal("No longer highlighting \"" + trimmed + "\".")
				.withStyle(ChatFormatting.GREEN));
	}

	private static void list(FabricClientCommandSource source) {
		List<String> words = ConfigManager.get().chat.highlightWords;

		if (words.isEmpty()) {
			say(source, Component.literal(
							"No highlight words. Add one with /skyaid highlight add <word>.")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		say(source, Component.literal("Highlighted words: ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(String.join(", ", words))
						.withStyle(ChatFormatting.WHITE)));
	}

	private static boolean contains(List<String> words, String candidate) {
		return words.stream().anyMatch(word ->
				word.toLowerCase(Locale.ROOT).equals(candidate.toLowerCase(Locale.ROOT)));
	}

	/** Padded like every other SkyAid chat block. */
	private static void say(FabricClientCommandSource source, Component message) {
		source.sendFeedback(Component.empty());
		source.sendFeedback(message);
		source.sendFeedback(Component.empty());
	}
}
