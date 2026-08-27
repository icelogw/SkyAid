package dev.skyaid.parse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Oruo's trivia: the known question-to-answers table, plus the one question
 * whose answer is computed - the current SkyBlock year. Table sourced from
 * the Skytils data repository (AGPL lineage, same family as the bundled room
 * data); unknown questions get no answer rather than a guess.
 */
public final class TriviaAnswers {
	/** SkyBlock year 1 began at this epoch second; a year lasts 446400s. */
	private static final long YEAR_EPOCH_SECONDS = 1_560_276_000L;
	private static final long YEAR_LENGTH_SECONDS = 446_400L;

	private static final Map<String, List<String>> ANSWERS = Map.ofEntries(
			Map.entry("What is the status of The Watcher?", List.of("Stalker")),
			Map.entry("What is the status of Bonzo?", List.of("New Necromancer")),
			Map.entry("What is the status of Scarf?", List.of("Apprentice Necromancer")),
			Map.entry("What is the status of The Professor?", List.of("Professor")),
			Map.entry("What is the status of Thorn?", List.of("Shaman Necromancer")),
			Map.entry("What is the status of Livid?", List.of("Master Necromancer")),
			Map.entry("What is the status of Sadan?", List.of("Necromancer Lord")),
			Map.entry("What is the status of Maxor, Storm, Goldor, and Necron?",
					List.of("The Wither Lords")),
			Map.entry("How many total Fairy Souls are there?", List.of("266 Fairy Souls")),
			Map.entry("How many Fairy Souls are there in Spider's Den?",
					List.of("19 Fairy Souls")),
			Map.entry("How many Fairy Souls are there in Spiders Den?",
					List.of("19 Fairy Souls")),
			Map.entry("How many Fairy Souls are there in The End?",
					List.of("12 Fairy Souls")),
			Map.entry("How many Fairy Souls are there in The Farming Islands?",
					List.of("20 Fairy Souls")),
			Map.entry("How many Fairy Souls are there in Crimson Isle?",
					List.of("29 Fairy Souls")),
			Map.entry("How many Fairy Souls are there in The Park?",
					List.of("12 Fairy Souls")),
			Map.entry("How many Fairy Souls are there in Jerry's Workshop?",
					List.of("5 Fairy Souls")),
			Map.entry("How many Fairy Souls are there in Hub?", List.of("80 Fairy Souls")),
			Map.entry("How many Fairy Souls are there in The Hub?",
					List.of("80 Fairy Souls")),
			Map.entry("How many Fairy Souls are there in Deep Caverns?",
					List.of("21 Fairy Souls")),
			Map.entry("How many Fairy Souls are there in Gold Mine?",
					List.of("12 Fairy Souls")),
			Map.entry("How many Fairy Souls are there in Dungeon Hub?",
					List.of("7 Fairy Souls")),
			Map.entry("Which brother is on the Spider's Den?", List.of("Rick")),
			Map.entry("Which brother is on the Spiders Den?", List.of("Rick")),
			Map.entry("What is the name of Rick's brother?", List.of("Pat")),
			Map.entry("What is the name of the vendor in the Hub who sells stained glass?",
					List.of("Wool Weaver")),
			Map.entry("What is the name of the person that upgrades pets?", List.of("Kat")),
			Map.entry("What is the name of the lady of the Nether?", List.of("Elle")),
			Map.entry("Which villager in the Village gives you a Rogue Sword?",
					List.of("Jamie")),
			Map.entry("How many unique minions are there?", List.of("60 Minions")),
			Map.entry("Which of these enemies does not spawn in the Spider's Den?",
					List.of("Zombie Spider", "Cave Spider", "Wither Skeleton",
							"Dashing Spooder", "Broodfather", "Night Spider")),
			Map.entry("Which of these enemies does not spawn in the Spiders Den?",
					List.of("Zombie Spider", "Cave Spider", "Wither Skeleton",
							"Dashing Spooder", "Broodfather", "Night Spider")),
			Map.entry("Which of these monsters only spawns at night?",
					List.of("Zombie Villager", "Ghast")),
			Map.entry("Which of these is not a dragon in The End?",
					List.of("Zoomer Dragon", "Weak Dragon", "Stonk Dragon",
							"Holy Dragon", "Boomer Dragon", "Booger Dragon",
							"Older Dragon", "Elder Dragon", "Stable Dragon",
							"Professor Dragon")));

	/** ⓐ ⓑ ⓒ - the answer option markers, as code points per house style. */
	private static final char OPTION_A = (char) 0x24D0;
	private static final char OPTION_C = (char) 0x24D2;

	private TriviaAnswers() {
	}

	/** Every accepted answer for a question; empty when unknown - no guessing. */
	public static List<String> answersFor(String question, long nowMillis) {
		if (question.equals("What SkyBlock year is it?")) {
			long diff = nowMillis / 1000 - YEAR_EPOCH_SECONDS;
			return List.of("Year " + (diff / YEAR_LENGTH_SECONDS + 1));
		}

		return ANSWERS.getOrDefault(question, List.of());
	}

	/** Whether a stripped chat line opens a new question. */
	public static boolean isQuestionHeader(String stripped) {
		return stripped.startsWith("Question #");
	}

	/** The answer text when the stripped line is an option; empty otherwise. */
	public static Optional<String> answerOption(String stripped) {
		if (stripped.length() < 3) {
			return Optional.empty();
		}

		char first = stripped.charAt(0);

		if (first < OPTION_A || first > OPTION_C) {
			return Optional.empty();
		}

		return Optional.of(stripped.substring(1).trim());
	}

	/** Whether the option line is the last of the three. */
	public static boolean isLastOption(String stripped) {
		return !stripped.isEmpty() && stripped.charAt(0) == OPTION_C;
	}
}
