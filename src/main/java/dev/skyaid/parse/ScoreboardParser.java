package dev.skyaid.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the Skyblock sidebar into a {@link SkyblockState}.
 *
 * <p>Pure functions over strings - no Minecraft types - so the whole thing is
 * unit testable against captured real sidebars. Hypixel rewords the sidebar
 * fairly often, so this class plus its fixture tests are the regression suite
 * that actually matters.
 *
 * <p>Most fields are matched line by line and order-independently, because the
 * sidebar gains and loses lines depending on the island and any active quest.
 * The slayer block is the exception: it is a heading followed by its own lines,
 * so it is read positionally.
 */
public final class ScoreboardParser {
	/** Purse is relabelled "Piggy" while a piggy bank is equipped. */
	private static final Pattern PURSE = Pattern.compile("(?:Purse|Piggy):[ ]*([0-9,]+)");
	private static final Pattern BITS = Pattern.compile("Bits:[ ]*([0-9,]+)");

	/**
	 * Instance ids look like m4A, mini45C, mega77J, dungeon12A: a lowercase
	 * prefix, digits, then an uppercase suffix. Searched rather than anchored,
	 * because Hypixel puts the id on the same line as the real-world date
	 * ("08/19/26 m4A"). Requiring the uppercase tail keeps it from matching
	 * ordinary words or the "21st" in a Skyblock date.
	 */
	private static final Pattern SERVER_ID = Pattern.compile("([a-z]+[0-9]+[A-Z]+)");

	private static final Pattern DATE = Pattern.compile(
			"^((?:Early |Late )?(?:Spring|Summer|Autumn|Winter) [0-9]{1,2}(?:st|nd|rd|th))$");

	private static final Pattern TIME = Pattern.compile("([0-9]{1,2}:[0-9]{2}[ ]*(?:am|pm))",
			Pattern.CASE_INSENSITIVE);

	/** Heading that introduces the slayer block. */
	private static final String SLAYER_HEADING = "Slayer Quest";

	/** Heading Hypixel puts above the current quest or objective. */
	private static final String OBJECTIVE_HEADING = "Objective";

	/** Hypixel's own advert line, which is never worth surfacing. */
	private static final String PROMO = "www.hypixel.net";

	/**
	 * Cap on passed-through lines, so an unfamiliar sidebar cannot grow the HUD
	 * without limit. Hypixel's own sidebar never approaches this.
	 */
	private static final int MAX_EXTRA_LINES = 8;

	private ScoreboardParser() {
	}

	/**
	 * @param title the sidebar objective title, still formatted
	 * @param lines the sidebar rows, still formatted, in display order
	 */
	public static SkyblockState parse(String title, List<String> lines) {
		if (lines == null) {
			return SkyblockState.EMPTY;
		}

		boolean inSkyblock = FormatCodes.strip(title).toUpperCase().contains("SKYBLOCK");

		List<String> stripped = new ArrayList<>(lines.size());

		for (String raw : lines) {
			stripped.add(FormatCodes.strip(raw));
		}

		// Tracks which lines a matcher claimed. Whatever is left over is content
		// this parser does not understand - a dungeon or mining sidebar, say - and
		// is handed back verbatim rather than dropped. Hiding Hypixel's sidebar is
		// on by default, so anything not claimed here would otherwise just vanish.
		boolean[] claimed = new boolean[stripped.size()];

		Optional<String> location = Optional.empty();
		Optional<String> serverId = Optional.empty();
		OptionalLong purse = OptionalLong.empty();
		OptionalLong bits = OptionalLong.empty();
		Optional<String> date = Optional.empty();
		Optional<String> time = Optional.empty();

		for (int i = 0; i < stripped.size(); i++) {
			String line = stripped.get(i);

			if (line.isEmpty() || line.equals(PROMO)) {
				claimed[i] = true;
				continue;
			}

			if (location.isEmpty()) {
				location = parseLocation(line);
				claimed[i] |= location.isPresent();
			}

			if (purse.isEmpty()) {
				purse = matchNumber(PURSE, line);
				claimed[i] |= purse.isPresent();
			}

			if (bits.isEmpty()) {
				bits = matchNumber(BITS, line);
				claimed[i] |= bits.isPresent();
			}

			if (date.isEmpty()) {
				date = matchGroup(DATE, line);
				claimed[i] |= date.isPresent();
			}

			if (time.isEmpty()) {
				time = matchGroup(TIME, line);
				claimed[i] |= time.isPresent();
			}

			if (serverId.isEmpty()) {
				serverId = matchGroup(SERVER_ID, line);
				claimed[i] |= serverId.isPresent();
			}
		}

		List<String> slayer = parseBlock(stripped, claimed, SLAYER_HEADING);
		List<String> objective = parseBlock(stripped, claimed, OBJECTIVE_HEADING);
		List<String> extras = new ArrayList<>();

		for (int i = 0; i < stripped.size() && extras.size() < MAX_EXTRA_LINES; i++) {
			if (!claimed[i]) {
				extras.add(stripped.get(i));
			}
		}

		return new SkyblockState(inSkyblock, location, serverId, purse, bits, date, time,
				first(slayer), second(slayer),
				first(objective), second(objective),
				List.copyOf(extras));
	}

	/**
	 * Reads the lines that follow a section heading: the name and its progress.
	 *
	 * <p>Positional rather than pattern-matched, because the content is arbitrary
	 * ("Revenant Horror II", "Talk to the Trapper") and the progress line varies
	 * ("Boss slain!", a percentage, an XP counter). There is nothing stable to
	 * match on except the position under the heading.
	 *
	 * <p>Shared by the slayer and objective blocks - they differ only in heading,
	 * and having one implementation means a fix to the block rules reaches both.
	 */
	private static List<String> parseBlock(
			List<String> stripped, boolean[] claimed, String heading) {
		int start = stripped.indexOf(heading);

		if (start < 0) {
			return List.of();
		}

		claimed[start] = true;
		List<String> found = new ArrayList<>(2);

		for (int i = start + 1; i < stripped.size() && found.size() < 2; i++) {
			String line = stripped.get(i);

			// A blank line ends the block; the promo sits below it and is not part of it.
			if (line.isEmpty() || line.equals(PROMO)) {
				break;
			}

			found.add(line);
			claimed[i] = true;
		}

		return found;
	}

	private static Optional<String> first(List<String> block) {
		return block.isEmpty() ? Optional.empty() : Optional.of(block.get(0));
	}

	private static Optional<String> second(List<String> block) {
		return block.size() < 2 ? Optional.empty() : Optional.of(block.get(1));
	}
	/**
	 * A location line is a zone glyph followed by the island name.
	 *
	 * <p>Matching against a list of known glyphs turned out to be wrong: a real
	 * capture showed Hypixel using a map-pin emoji rather than the benzene ring
	 * this originally looked for, so the location silently went missing. Instead,
	 * any line starting with something that is not a letter or digit is treated as
	 * a location, and the glyph - whatever it is - is stripped off the front.
	 *
	 * <p>That is safe against the rest of the sidebar because every other line
	 * starts with a letter ("Purse:", "Slayer Quest") or a digit (the date line).
	 * Working by code point rather than by char matters here: emoji glyphs are
	 * surrogate pairs, and substring(1) would split one in half.
	 */
	private static Optional<String> parseLocation(String line) {
		int first = line.codePointAt(0);

		if (Character.isLetterOrDigit(first)) {
			return Optional.empty();
		}

		int index = 0;

		while (index < line.length()) {
			int codePoint = line.codePointAt(index);

			if (Character.isLetterOrDigit(codePoint)) {
				break;
			}

			index += Character.charCount(codePoint);
		}

		String name = line.substring(index).trim();

		// Require a letter so decorative separator lines are not mistaken for places.
		return name.isEmpty() || name.chars().noneMatch(Character::isLetter)
				? Optional.empty()
				: Optional.of(name);
	}

	private static OptionalLong matchNumber(Pattern pattern, String line) {
		Matcher m = pattern.matcher(line);

		if (!m.find()) {
			return OptionalLong.empty();
		}

		// Keep only the digits. FormatCodes should already have removed anything
		// else, but this is the place a stray character silently truncates a purse,
		// so it is worth being defensive twice rather than trusting one layer.
		StringBuilder digits = new StringBuilder();

		for (char c : m.group(1).toCharArray()) {
			if (Character.isDigit(c)) {
				digits.append(c);
			}
		}

		if (digits.isEmpty()) {
			return OptionalLong.empty();
		}

		try {
			return OptionalLong.of(Long.parseLong(digits.toString()));
		} catch (NumberFormatException e) {
			// A purse big enough to overflow a long is not worth crashing the HUD over.
			return OptionalLong.empty();
		}
	}

	private static Optional<String> matchGroup(Pattern pattern, String line) {
		Matcher m = pattern.matcher(line);
		return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
	}
}
