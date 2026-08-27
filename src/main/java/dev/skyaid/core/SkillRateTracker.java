package dev.skyaid.core;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XP rate for the skill the tab list shows, watched as its percentage creeps
 * up - "Farming 22: 87.3%" sampled over a window, reported as percent per
 * hour. Honest about its limits: the tab shows one decimal, so slow skills
 * read 0 for a while before the first tenth lands.
 */
public final class SkillRateTracker {
	/** "Farming 22: 87.3%" - name, level, percent. */
	private static final Pattern LINE = Pattern.compile("^(.+?) (\\d+): ([0-9.]+)%$");

	private static final long WINDOW_MILLIS = 15 * 60_000;
	private static final long SAMPLE_GAP_MILLIS = 1_000;

	private record Sample(long at, double pct) {
	}

	private static final ArrayDeque<Sample> samples = new ArrayDeque<>();
	private static String skill = "";
	private static int level = -1;
	private static long lastSampleAt;

	private SkillRateTracker() {
	}

	/** Fed the tab list's skill line whenever the HUD walks the tab. */
	public static void observe(String line) {
		long now = System.currentTimeMillis();

		if (now - lastSampleAt < SAMPLE_GAP_MILLIS) {
			return;
		}

		Matcher matcher = LINE.matcher(line.trim());

		if (!matcher.matches()) {
			return;
		}

		lastSampleAt = now;
		String name = matcher.group(1);
		int parsedLevel = Integer.parseInt(matcher.group(2));

		// A new skill or a level-up restarts the window - percentages from
		// different levels are different units.
		if (!name.equals(skill) || parsedLevel != level) {
			samples.clear();
			skill = name;
			level = parsedLevel;
		}

		samples.addLast(new Sample(now, Double.parseDouble(matcher.group(3))));

		while (!samples.isEmpty()
				&& now - samples.peekFirst().at() > WINDOW_MILLIS) {
			samples.removeFirst();
		}
	}

	/** "Farming +4.2%/hr" once the window shows real movement. */
	public static Optional<String> perHour() {
		Sample first = samples.peekFirst();
		Sample last = samples.peekLast();

		if (first == null || last == null || last.at() - first.at() < 60_000
				|| last.pct() <= first.pct()) {
			return Optional.empty();
		}

		double perHour = (last.pct() - first.pct()) * 3_600_000.0
				/ (last.at() - first.at());
		return Optional.of(String.format(Locale.ROOT,
				"%s +%.1f%%/hr", skill, perHour));
	}
}
