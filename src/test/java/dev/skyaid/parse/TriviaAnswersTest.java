package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Trivia and Three Weirdos tables")
class TriviaAnswersTest {
	private static final char OPTION_A = (char) 0x24D0;
	private static final char OPTION_C = (char) 0x24D2;

	@Test
	void knownQuestionsAnswer() {
		assertEquals(List.of("Stalker"),
				TriviaAnswers.answersFor("What is the status of The Watcher?", 0));
		assertTrue(TriviaAnswers
				.answersFor("Some question Hypixel added yesterday?", 0).isEmpty());
	}

	@Test
	void skyblockYearIsComputedFromTheClock() {
		// Exactly three full years plus a bit after the epoch -> Year 4.
		long now = (1_560_276_000L + 3 * 446_400L + 1000) * 1000;
		assertEquals(List.of("Year 4"), TriviaAnswers.answersFor(
				"What SkyBlock year is it?", now));
	}

	@Test
	void optionLinesParse() {
		assertEquals("Stalker", TriviaAnswers
				.answerOption(OPTION_A + " Stalker").orElseThrow());
		assertTrue(TriviaAnswers.answerOption("Question #1").isEmpty());
		assertTrue(TriviaAnswers.isLastOption(OPTION_C + " Wrong Answer"));
		assertTrue(TriviaAnswers.isQuestionHeader("Question #2"));
	}

	@Test
	void weirdosPhraseNamesTheTruthfulNpc() {
		assertEquals("Baxter", WeirdosPhrases.truthfulNpc(
				"[NPC] Baxter: My chest has the reward and I'm telling the truth!")
				.orElseThrow());
		assertTrue(WeirdosPhrases.truthfulNpc(
				"[NPC] Hope: The reward is in my chest.").isEmpty());
		assertTrue(WeirdosPhrases.truthfulNpc("Baxter: no NPC prefix").isEmpty());
	}
}
