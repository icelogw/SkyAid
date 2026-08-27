package dev.skyaid.dungeon.solvers;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.dungeon.core.DungeonTracker;
import dev.skyaid.parse.FormatCodes;
import dev.skyaid.parse.TriviaAnswers;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Oruo's trivia: the question arrives in chat over several lines, then three
 * lettered options. When the question is known, the correct option is said
 * back in a single green client line - "Answer: Stalker" - the moment the
 * options finish. Unknown questions stay silent: a wrong hint is worse than
 * none.
 */
final class TriviaSolver implements PuzzleSolver {
	private final List<String> questionLines = new ArrayList<>();
	private boolean collecting;

	TriviaSolver() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !ConfigManager.get().enabled
					|| !ConfigManager.get().puzzleSolvers || !inTriviaRoom()) {
				return;
			}

			String stripped = FormatCodes.strip(message.getString());

			if (TriviaAnswers.isQuestionHeader(stripped)) {
				questionLines.clear();
				collecting = true;
				return;
			}

			if (!collecting) {
				return;
			}

			var option = TriviaAnswers.answerOption(stripped);

			if (option.isEmpty()) {
				if (!stripped.isBlank()) {
					questionLines.add(stripped);
				}

				return;
			}

			// First option seen: the accumulated lines are the question.
			String question = String.join(" ", questionLines).trim();
			List<String> correct = TriviaAnswers.answersFor(
					question, System.currentTimeMillis());

			if (correct.contains(option.get())) {
				say(Component.literal("Answer: ").withStyle(ChatFormatting.GRAY)
						.append(Component.literal(option.get())
								.withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
				collecting = false;
				return;
			}

			if (TriviaAnswers.isLastOption(stripped)) {
				collecting = false;

				if (correct.isEmpty() && !question.isEmpty()) {
					say(Component.literal(
									"Unknown trivia question - answer not in the table:")
							.withStyle(ChatFormatting.GRAY)
							.append(Component.literal("\n  " + question)
									.withStyle(ChatFormatting.WHITE)));
				}
			}
		});
	}

	private static boolean inTriviaRoom() {
		return DungeonTracker.currentRoom()
				.map(room -> room.name().equals("Trivia-Room")).orElse(false);
	}

	@Override
	public boolean handles(String roomName) {
		return roomName.equals("Trivia-Room");
	}

	@Override
	public void tick(Minecraft client, DungeonTracker.Room room) {
	}

	@Override
	public void reset() {
		questionLines.clear();
		collecting = false;
	}

	private static void say(Component message) {
		var client = Minecraft.getInstance();

		if (client.gui != null) {
			// A blank line either side, the same breathing room the help
			// and session blocks get.
			var chat = client.gui.hud.getChat();
			chat.addClientSystemMessage(Component.empty());
			chat.addClientSystemMessage(message);
			chat.addClientSystemMessage(Component.empty());
		}
	}
}
