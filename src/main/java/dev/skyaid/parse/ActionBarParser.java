package dev.skyaid.parse;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads health, defence and mana off the Skyblock action bar.
 *
 * <p>Anchored on the words "Defense" and "Mana" rather than on the glyphs beside
 * them. Hypixel renders those glyphs from its own font using private-use code
 * points - a real capture showed the location pin as U+E067, not the emoji this
 * code originally assumed - so any pattern that hardcodes a glyph silently stops
 * matching the moment Hypixel changes its font. The words are stable; the
 * symbols are not.
 *
 * <p>The action bar is a single line whose segments come and go: skill XP popups,
 * drill fuel and ability cooldowns all borrow space from it, so each value is
 * matched independently and any of them may be absent on a given tick.
 */
public final class ActionBarParser {
	/**
	 * A number, then anything that is not a digit, then the label. The gap cannot
	 * span digits, so this always binds the label to the number nearest it.
	 */
	private static final Pattern DEFENSE = Pattern.compile("([0-9,]+)[^0-9]*Defense");
	private static final Pattern MANA = Pattern.compile("([0-9,]+)/([0-9,]+)[^0-9]*Mana");

	/** In dungeons: "3/10 Secrets", the current room's found/total count. */
	private static final Pattern SECRETS = Pattern.compile("([0-9,]+)/([0-9,]+)[^0-9]*Secrets");

	/** Health carries no label, so it is found as a current/max pair. */
	private static final Pattern PAIR = Pattern.compile("([0-9,]+)/([0-9,]+)");

	private ActionBarParser() {
	}

	public static ActionBarState parse(String rawActionBar) {
		String line = FormatCodes.strip(rawActionBar);

		if (line.isEmpty()) {
			return ActionBarState.EMPTY;
		}

		Matcher mana = MANA.matcher(line);
		boolean hasMana = mana.find();

		OptionalLong currentMana = hasMana ? number(mana.group(1)) : OptionalLong.empty();
		OptionalLong maxMana = hasMana ? number(mana.group(2)) : OptionalLong.empty();

		Matcher defense = DEFENSE.matcher(line);
		OptionalLong defenseValue =
				defense.find() ? number(defense.group(1)) : OptionalLong.empty();

		Matcher secrets = SECRETS.matcher(line);
		boolean hasSecrets = secrets.find();

		OptionalLong secretsFound = hasSecrets ? number(secrets.group(1)) : OptionalLong.empty();
		OptionalLong secretsTotal = hasSecrets ? number(secrets.group(2)) : OptionalLong.empty();

		// Health is the first current/max pair that is neither the mana nor the
		// secrets one - both are also "N/M" shaped, and each is claimed by its
		// label above so a bar showing only them is not misread as health.
		OptionalLong currentHealth = OptionalLong.empty();
		OptionalLong maxHealth = OptionalLong.empty();
		Matcher pair = PAIR.matcher(line);

		while (pair.find()) {
			if (hasMana && pair.start() == mana.start()) {
				continue;
			}

			if (hasSecrets && pair.start() == secrets.start()) {
				continue;
			}

			currentHealth = number(pair.group(1));
			maxHealth = number(pair.group(2));
			break;
		}

		return new ActionBarState(currentHealth, maxHealth, defenseValue,
				currentMana, maxMana, secretsFound, secretsTotal);
	}

	private static OptionalLong number(String text) {
		try {
			return OptionalLong.of(Long.parseLong(text.replace(",", "")));
		} catch (NumberFormatException e) {
			return OptionalLong.empty();
		}
	}
}
