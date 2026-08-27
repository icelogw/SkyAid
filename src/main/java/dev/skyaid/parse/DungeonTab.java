package dev.skyaid.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the dungeon run state out of tab-list lines: secrets percentage,
 * deaths, crypts, completed rooms, and each puzzle's status. Patterns are
 * anchored on the stable words, not the decorative glyphs, the same doctrine
 * as the action bar parser.
 *
 * <p>Wording is ecosystem knowledge pending a real capture - the dump's TAB
 * LIST section exists precisely to correct this class from the field.
 */
public final class DungeonTab {
	/** One puzzle as the tab list reports it. */
	public record Puzzle(String name, String state) {
	}

	/** The parsed run state; anything the tab did not say is empty. */
	public record State(
			OptionalLong secretsPercent,
			OptionalLong deaths,
			OptionalLong crypts,
			OptionalLong completedRooms,
			OptionalLong puzzleCount,
			List<Puzzle> puzzles,
			boolean mimicDead) {
		public static final State EMPTY = new State(OptionalLong.empty(),
				OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
				OptionalLong.empty(), List.of(), false);
	}

	private static final Pattern SECRETS = Pattern.compile(
			"Secrets Found:?\\s*([0-9.,]+)%");
	private static final Pattern DEATHS = Pattern.compile(
			"Team Deaths:?\\s*\\(?([0-9]+)\\)?|Deaths:?\\s*\\(?([0-9]+)\\)?");
	private static final Pattern CRYPTS = Pattern.compile("Crypts:?\\s*([0-9]+)");
	private static final Pattern ROOMS = Pattern.compile(
			"Completed Rooms:?\\s*([0-9]+)");
	private static final Pattern PUZZLE_COUNT = Pattern.compile(
			"Puzzles:?\\s*\\(?([0-9]+)\\)?");

	/**
	 * A puzzle line: a name then a bracketed status glyph - check for solved,
	 * cross for failed, blank for pending. No indentation in the pattern:
	 * {@link FormatCodes#strip} normalises leading whitespace away, so the
	 * puzzle block's bounds come from the Puzzles header and its count.
	 */
	private static final Pattern PUZZLE = Pattern.compile(
			"^([A-Za-z' -]+?):?\\s*\\[([^\\]]*)]$");

	private static final Pattern MIMIC = Pattern.compile("Mimic Dead:?\\s*YES");

	private DungeonTab() {
	}

	public static State parse(List<String> lines) {
		OptionalLong secrets = OptionalLong.empty();
		OptionalLong deaths = OptionalLong.empty();
		OptionalLong crypts = OptionalLong.empty();
		OptionalLong rooms = OptionalLong.empty();
		OptionalLong puzzleCount = OptionalLong.empty();
		List<Puzzle> puzzles = new ArrayList<>();
		boolean mimic = false;
		boolean inPuzzleBlock = false;

		for (String raw : lines) {
			String line = FormatCodes.strip(raw);

			Matcher matcher = SECRETS.matcher(line);

			if (matcher.find()) {
				secrets = round(matcher.group(1));
				continue;
			}

			matcher = DEATHS.matcher(line);

			if (matcher.find()) {
				String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
				deaths = number(value);
				continue;
			}

			matcher = CRYPTS.matcher(line);

			if (matcher.find()) {
				crypts = number(matcher.group(1));
				continue;
			}

			matcher = ROOMS.matcher(line);

			if (matcher.find()) {
				rooms = number(matcher.group(1));
				continue;
			}

			matcher = PUZZLE_COUNT.matcher(line);

			if (matcher.find()) {
				puzzleCount = number(matcher.group(1));
				inPuzzleBlock = true;
				continue;
			}

			if (MIMIC.matcher(line).find()) {
				mimic = true;
				continue;
			}

			if (inPuzzleBlock) {
				matcher = PUZZLE.matcher(line);

				if (matcher.find()) {
					puzzles.add(new Puzzle(matcher.group(1).trim(),
							stateOf(matcher.group(2))));

					if (puzzleCount.isPresent()
							&& puzzles.size() >= puzzleCount.getAsLong()) {
						inPuzzleBlock = false;
					}
				} else if (!line.isBlank()) {
					inPuzzleBlock = false;
				}
			}
		}

		return new State(secrets, deaths, crypts, rooms, puzzleCount,
				List.copyOf(puzzles), mimic);
	}

	// Written as code points, not literals: glyphs in source written through
	// tooling have been mangled by encoding before (see the parse doctrine).
	private static final char HEAVY_CHECK = (char) 0x2714;
	private static final char CHECK = (char) 0x2713;
	private static final char HEAVY_CROSS = (char) 0x2716;
	private static final char CROSS = (char) 0x2717;

	/** The bracket glyph, translated: check = solved, cross = failed. */
	private static String stateOf(String glyph) {
		if (glyph.indexOf(HEAVY_CHECK) >= 0 || glyph.indexOf(CHECK) >= 0) {
			return "solved";
		}

		if (glyph.indexOf(HEAVY_CROSS) >= 0 || glyph.indexOf(CROSS) >= 0) {
			return "failed";
		}

		return "pending";
	}

	private static OptionalLong number(String text) {
		try {
			return OptionalLong.of(Long.parseLong(text.replace(",", "")));
		} catch (NumberFormatException e) {
			return OptionalLong.empty();
		}
	}

	private static OptionalLong round(String text) {
		try {
			return OptionalLong.of(Math.round(
					Double.parseDouble(text.replace(",", ""))));
		} catch (NumberFormatException e) {
			return OptionalLong.empty();
		}
	}

	/** The named puzzle's tab status, when the tab lists it. */
	public static Optional<String> puzzleState(State state, String name) {
		for (Puzzle puzzle : state.puzzles()) {
			if (puzzle.name().equalsIgnoreCase(name)) {
				return Optional.of(puzzle.state());
			}
		}

		return Optional.empty();
	}
}
