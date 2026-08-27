package dev.skyaid.core;

import dev.skyaid.parse.ServerAddresses;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Answers one question: is the client currently connected to Hypixel?
 *
 * <p>Every feature gates on {@link #isOnHypixel()} and returns early when it is
 * false. That keeps singleplayer and every other server completely untouched, and
 * is the single most important safety property of the mod.
 *
 * <p>The address matching itself lives in
 * {@link ServerAddresses#isHypixel(String)} so it can be unit tested without
 * Minecraft on the classpath.
 */
public final class HypixelDetector {
	private HypixelDetector() {
	}

	public static boolean isOnHypixel() {
		Minecraft client = Minecraft.getInstance();

		if (client.isLocalServer()) {
			return false;
		}

		ServerData server = client.getCurrentServer();
		return server != null && ServerAddresses.isHypixel(server.ip);
	}
}
