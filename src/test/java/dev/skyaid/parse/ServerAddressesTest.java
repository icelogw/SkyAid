package dev.skyaid.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Hypixel server address matching")
class ServerAddressesTest {
	@Test
	void acceptsHypixelAndItsSubdomains() {
		assertTrue(ServerAddresses.isHypixel("hypixel.net"));
		assertTrue(ServerAddresses.isHypixel("mc.hypixel.net"));
		assertTrue(ServerAddresses.isHypixel("alpha.hypixel.net"));
		assertTrue(ServerAddresses.isHypixel("MC.HYPIXEL.NET"));
		assertTrue(ServerAddresses.isHypixel("mc.hypixel.net:25565"));
		assertTrue(ServerAddresses.isHypixel("  mc.hypixel.net  "));
		assertTrue(ServerAddresses.isHypixel("mc.hypixel.net."));
	}

	@Test
	@DisplayName("rejects lookalike domains that a substring check would accept")
	void rejectsLookalikes() {
		// The reason this predicate is not a contains() call: each of these would
		// otherwise pass, and this check gates whether the API key is ever sent.
		assertFalse(ServerAddresses.isHypixel("hypixel.net.evil.example"));
		assertFalse(ServerAddresses.isHypixel("nothypixel.net"));
		assertFalse(ServerAddresses.isHypixel("fakehypixel.net"));
		assertFalse(ServerAddresses.isHypixel("hypixel.net.co"));
	}

	@Test
	void rejectsOtherServersAndMissingInput() {
		assertFalse(ServerAddresses.isHypixel("localhost"));
		assertFalse(ServerAddresses.isHypixel("play.cubecraft.net"));
		assertFalse(ServerAddresses.isHypixel(""));
		assertFalse(ServerAddresses.isHypixel("   "));
		assertFalse(ServerAddresses.isHypixel(null));
	}
}
