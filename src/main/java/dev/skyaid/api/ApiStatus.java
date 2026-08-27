package dev.skyaid.api;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * The one place the API connection state is worded, so the popup and the
 * {@code /skyaid key status} command always say the same thing.
 *
 * <p>The failure cases are kept apart on purpose. "Rejected" and "unreachable"
 * look identical from the outside but need opposite responses - replace the key
 * versus wait and retry - so telling somebody the wrong one sends them off
 * regenerating a key that was never broken.
 */
public final class ApiStatus {
	private ApiStatus() {
	}

	/** Renders whichever message matches the check result. */
	public static Component of(HypixelApiClient.KeyCheck result) {
		return switch (result) {
			case VALID -> connected();
			case NO_KEY -> needsKey();
			case REJECTED -> rejected();
			case UNREACHABLE -> unreachable();
		};
	}

	public static Component connected() {
		return Component.literal("API connected").withStyle(ChatFormatting.GREEN);
	}

	/** No key stored at all. */
	public static Component needsKey() {
		return disconnected("Requires API key");
	}

	/** Hypixel answered and refused the key - it is wrong, expired, or revoked. */
	public static Component rejected() {
		return disconnected("Key rejected by Hypixel");
	}

	/** No answer at all: offline, timed out, or rate limited. The key may be fine. */
	public static Component unreachable() {
		return disconnected("Could not reach Hypixel");
	}

	private static Component disconnected(String reason) {
		return Component.literal("API disconnected ").withStyle(ChatFormatting.RED)
				.append(Component.literal("[ " + reason + " ]")
						.withStyle(ChatFormatting.DARK_RED));
	}
}
