package dev.skyaid.parse;

import java.util.OptionalLong;

/**
 * Health, defence, mana and the dungeon secrets count as last shown on the
 * Skyblock action bar.
 *
 * <p>Fields are optional for the same reason as {@link SkyblockState}: Hypixel
 * swaps segments of the action bar out for skill-XP popups, drill fuel and
 * ability messages, so any given tick may simply not report a value.
 */
public record ActionBarState(
		OptionalLong health,
		OptionalLong maxHealth,
		OptionalLong defense,
		OptionalLong mana,
		OptionalLong maxMana,
		OptionalLong secretsFound,
		OptionalLong secretsTotal) {

	public static final ActionBarState EMPTY = new ActionBarState(
			OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
			OptionalLong.empty(), OptionalLong.empty(),
			OptionalLong.empty(), OptionalLong.empty());
}
