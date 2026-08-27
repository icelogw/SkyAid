package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Bazaar product matching")
class BazaarTest {
	private static final Set<String> IDS = Set.of(
			"ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND_BLOCK", "DIAMOND",
			"BOOSTER_COOKIE", "ENCHANTMENT_ULTIMATE_WISE_5");

	@Test
	void normalisesTypedNamesToIdSpelling() {
		assertEquals("ENCHANTED_DIAMOND", Bazaar.normalise(" Enchanted diamond "));
		assertEquals("BOOSTER_COOKIE", Bazaar.normalise("booster-cookie"));
		assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", Bazaar.normalise("enchantment ultimate wise 5"));
	}

	@Test
	void exactIdMatchWinsOutright() {
		assertEquals(List.of("DIAMOND"), Bazaar.match(IDS, "diamond"));
	}

	@Test
	@DisplayName("substring matches come shortest-first so the base item leads")
	void substringMatchesRankShortestFirst() {
		List<String> matches = Bazaar.match(IDS, "enchanted dia");

		assertEquals("ENCHANTED_DIAMOND", matches.get(0));
		assertTrue(matches.contains("ENCHANTED_DIAMOND_BLOCK"));
	}

	@Test
	@DisplayName("a fully typed name is not second-guessed by its variants")
	void exactNameBeatsLongerVariants() {
		assertEquals(List.of("ENCHANTED_DIAMOND"),
				Bazaar.match(IDS, "enchanted diamond"));
	}

	@Test
	void unknownQueriesMatchNothing() {
		assertTrue(Bazaar.match(IDS, "hyperion").isEmpty());
		assertTrue(Bazaar.match(IDS, "   ").isEmpty());
	}

	@Test
	@DisplayName("display names use Hypixel's own spelling")
	void displayNamesMatchHypixel() {
		assertEquals("Dragon Essence", Bazaar.displayName("ESSENCE_DRAGON"));
		assertEquals("Ultimate Wise 5",
				Bazaar.displayName("ENCHANTMENT_ULTIMATE_WISE_5"));
		assertEquals("Enchanted Diamond", Bazaar.displayName("ENCHANTED_DIAMOND"));
	}

	@Test
	@DisplayName("typing the display name finds the reversed id")
	void displayNameQueriesMatch() {
		assertEquals(List.of("ESSENCE_DRAGON"),
				Bazaar.match(Set.of("ESSENCE_DRAGON", "DIAMOND"), "dragon essence"));
	}

	@Test
	void prettyRendersIdsForHumans() {
		assertEquals("Enchanted Diamond Block", Bazaar.pretty("ENCHANTED_DIAMOND_BLOCK"));
	}
}
