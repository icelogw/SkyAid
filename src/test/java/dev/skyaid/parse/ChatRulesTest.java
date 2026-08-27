package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Hypixel chat classification")
class ChatRulesTest {
	private static final char S = (char) 0x00A7;

	@Test
	void recognisesLobbyJoinsIncludingTheChevronForm() {
		assertEquals(ChatRules.Kind.LOBBY_JOIN,
				ChatRules.classify(S + "b[MVP" + S + "c+" + S + "b] Notch"
						+ S + "e joined the lobby!"));
		assertEquals(ChatRules.Kind.LOBBY_JOIN,
				ChatRules.classify(">>> " + S + "bNotch joined the lobby! <<<"));
	}

	@Test
	void recognisesChannels() {
		assertEquals(ChatRules.Kind.PARTY,
				ChatRules.classify(S + "9Party " + S + "8> " + S + "bNotch" + S + "f: hi"));
		assertEquals(ChatRules.Kind.GUILD,
				ChatRules.classify(S + "2Guild > " + S + "bNotch" + S + "f: hi"));
		assertEquals(ChatRules.Kind.DIRECT_MESSAGE,
				ChatRules.classify(S + "dFrom " + S + "b[MVP+] Notch" + S + "f: hi"));
		assertEquals(ChatRules.Kind.DIRECT_MESSAGE,
				ChatRules.classify(S + "dTo " + S + "bNotch" + S + "f: hi"));
	}

	@Test
	@DisplayName("store adverts are recognised, ordinary talk is not")
	void recognisesPromotions() {
		assertTrue(ChatRules.isPromotion(S + "estore.hypixel.net"));
		assertTrue(ChatRules.isPromotion("Visit hypixel.net/store for ranks!"));
		assertTrue(ChatRules.isPromotion(S + "ewww.hypixel.net"));

		// The reason this matches on the address and not on words like "sale":
		// players talk about sales constantly.
		assertFalse(ChatRules.isPromotion("Notch: selling dragon armour cheap"));
		assertFalse(ChatRules.isPromotion("Notch: big sale at the auction house"));
	}

	@Test
	@DisplayName("ability cooldown spam is recognised, talk about cooldowns is not")
	void recognisesAbilityCooldownSpam() {
		assertTrue(ChatRules.isAbilityCooldown(
				S + "cThis ability is on cooldown for 42s."));
		assertTrue(ChatRules.isAbilityCooldown("This ability is on cooldown for 1s."));

		assertFalse(ChatRules.isAbilityCooldown("Notch: this ability is on cooldown too long"));
		assertFalse(ChatRules.isAbilityCooldown("Your ability is ready!"));
	}

	@Test
	@DisplayName("auction announcements are recognised, auction talk is not")
	void recognisesAuctionAnnouncements() {
		assertTrue(ChatRules.isAuctionAnnouncement(
				S + "6[Auction] " + S + "bNotch " + S + "ebought " + S + "fAspect of the End"));
		assertTrue(ChatRules.isAuctionAnnouncement("[Auction] Your auction has expired!"));

		assertFalse(ChatRules.isAuctionAnnouncement("Notch: check the [Auction] house"));
		assertFalse(ChatRules.isAuctionAnnouncement("Auction starting soon"));
	}

	@Test
	@DisplayName("sack notices are recognised, talk about sacks is not")
	void recognisesSackMessages() {
		assertTrue(ChatRules.isSackMessage(S + "6[Sacks] " + S + "a+240 items" + S + "7 (Last 3s.)"));
		assertTrue(ChatRules.isSackMessage("[Sacks] +1 item."));

		assertFalse(ChatRules.isSackMessage("Notch: check your [Sacks] lol"));
		assertFalse(ChatRules.isSackMessage("Sacks are full!"));
	}

	@Test
	@DisplayName("a mention is somebody else saying your name, not you sending it")
	void recognisesMentions() {
		assertTrue(ChatRules.mentions(S + "b[MVP+] Notch" + S + "f: hey icelogw", "icelogw"));
		assertTrue(ChatRules.mentions("Notch: ICELOGW look at this", "icelogw"));

		// Every message you send starts with your own name. Matching the whole line
		// would make everything you type a mention of yourself.
		assertFalse(ChatRules.mentions("icelogw: hello everyone", "icelogw"));

		assertFalse(ChatRules.mentions("Notch: hello everyone", "icelogw"));
		assertFalse(ChatRules.mentions("Notch: hello", ""));
		assertFalse(ChatRules.mentions("Notch: hello", null));
	}

	@Test
	@DisplayName("ordinary chat and unrelated lines are left alone")
	void everythingElseIsOther() {
		assertEquals(ChatRules.Kind.OTHER,
				ChatRules.classify(S + "b[MVP+] Notch" + S + "f: hello everyone"));
		assertEquals(ChatRules.Kind.OTHER, ChatRules.classify("You joined the party!"));
		assertEquals(ChatRules.Kind.OTHER, ChatRules.classify(""));
		assertEquals(ChatRules.Kind.OTHER, ChatRules.classify(null));
	}
}
