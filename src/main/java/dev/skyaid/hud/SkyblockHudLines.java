package dev.skyaid.hud;

import dev.skyaid.config.Config;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.parse.ActionBarState;
import dev.skyaid.parse.DungeonLines;
import dev.skyaid.parse.EventTimers;
import dev.skyaid.parse.HudLayout;
import dev.skyaid.parse.Numbers;
import dev.skyaid.parse.SessionStats;
import dev.skyaid.parse.SkyblockState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Builds the rows the Skyblock HUD shows, in the configured order.
 *
 * <p>Split out from rendering so the position screen can preview exactly what the
 * HUD will draw, rather than approximating it - if the preview and the real thing
 * were built separately they would drift apart the first time a line changed.
 *
 * <p>Colours follow Hypixel's own conventions - gold for coins, aqua for bits,
 * green for where you are - so the readout can be scanned by colour the way the
 * sidebar it replaces was. Labels are dimmed so the numbers carry the emphasis.
 */
public final class SkyblockHudLines {
	private static final ChatFormatting LABEL = ChatFormatting.GRAY;

	/**
	 * Free-text lines wrap at this width (before HUD scale). Without it a
	 * single long objective - "Complete The Precursor Ruins Race in 50s
	 * (Anything, No Runback)" was the real case - stretches the panel across
	 * half the screen.
	 */
	/** Where free text wraps; the slider on the HUD tab drives it. */
	private static int wrapWidth() {
		return Math.max(100, Math.min(300, ConfigManager.get().skyblockHud.wrapWidth));
	}

	/** Stand-in state for the position screen, where no real sidebar may exist. */
	private static final SkyblockState SAMPLE = new SkyblockState(true,
			Optional.of("Your Island"),
			Optional.empty(),
			OptionalLong.of(7_884_267L),
			OptionalLong.of(15_615L),
			Optional.of("Early Summer 26th"),
			Optional.of("11:00pm"),
			Optional.of("Revenant Horror II"),
			Optional.of("Boss slain!"),
			Optional.of("Talk to the Trapper"),
			Optional.empty(),
			List.of("Carnival 111:58:05"));

	/** Forty-five minutes in, up 1.3M coins - so every session line has content. */
	private static final SessionStats.Snapshot SAMPLE_SESSION = new SessionStats.Snapshot(
			OptionalLong.of(1_284_500L),
			OptionalLong.of(240L),
			OptionalLong.of(1_712_600L),
			45 * 60_000L);

	private static final ActionBarState SAMPLE_BAR = new ActionBarState(
			OptionalLong.of(745), OptionalLong.of(745),
			OptionalLong.of(521),
			OptionalLong.of(534), OptionalLong.of(534),
			OptionalLong.empty(), OptionalLong.empty());

	private SkyblockHudLines() {
	}

	/** The HUD composes every frame; the tab regexes need not run that often. */
	private static dev.skyaid.parse.DungeonTab.State cachedTab =
			dev.skyaid.parse.DungeonTab.State.EMPTY;
	private static long cachedTabAt;

	private static dev.skyaid.parse.DungeonTab.State cachedDungeonTab() {
		long now = System.currentTimeMillis();

		if (now - cachedTabAt > 500) {
			cachedTabAt = now;
			cachedTab = dev.skyaid.parse.DungeonTab.parse(
					dev.skyaid.core.TabListReader.lines());
		}

		return cachedTab;
	}

	/** The first integer in a line: "Cleared: 47% (118)" -> 47. */
	private static java.util.OptionalLong firstNumber(String line) {
		int start = -1;

		for (int i = 0; i < line.length(); i++) {
			boolean digit = Character.isDigit(line.charAt(i));

			if (digit && start < 0) {
				start = i;
			} else if (!digit && start >= 0) {
				return java.util.OptionalLong.of(Long.parseLong(line.substring(start, i)));
			}
		}

		return start < 0 ? java.util.OptionalLong.empty()
				: java.util.OptionalLong.of(Long.parseLong(line.substring(start)));
	}

	/**
	 * Sample rows, built through the same path as the real ones so the position
	 * screen previews the actual order, colours and toggles rather than a mock-up.
	 */
	public static List<HudLine> sample() {
		return build(SAMPLE, SAMPLE_SESSION, SAMPLE_BAR);
	}

	/**
	 * The rows for a given state: each enabled element that has something to show,
	 * in the configured order, with dividers reduced to those that still separate
	 * two visible things.
	 */
	public static List<HudLine> build(
			SkyblockState state, SessionStats.Snapshot session, ActionBarState bar) {
		Config.HudSettings hud = ConfigManager.get().skyblockHud;

		// A zone profile takes over wherever its zone matches the location, so
		// the dungeon HUD can look different from the hub one.
		Config.HudProfile profile = hud.activeProfile(state.location().orElse(null));
		Config.HudElements elements = profile != null ? profile.elements : hud.elements;
		List<String> layout = profile != null ? profile.layout : hud.layout;

		Map<String, List<Component>> content =
				contentByElement(state, session, bar, elements, hud.shortNumbers);

		List<String> order = HudLayout.normalise(
				HudLayout.sanitise(layout), content.keySet());

		List<HudLine> rows = new ArrayList<>();

		for (String id : order) {
			if (HudLayout.DIVIDER.equals(id)) {
				rows.add(HudLine.separator());
				continue;
			}

			for (Component line : content.get(id)) {
				rows.add(HudLine.of(line));
			}
		}

		return rows;
	}

	/**
	 * What each element would draw, keyed by id. Elements that are switched off or
	 * have no value are absent entirely, which is what lets divider placement work
	 * off "did this produce anything" rather than off the switches alone.
	 */
	private static Map<String, List<Component>> contentByElement(
			SkyblockState state, SessionStats.Snapshot session, ActionBarState bar,
			Config.HudElements elements, boolean shortForm) {
		Map<String, List<Component>> content = new LinkedHashMap<>();

		// Player info, straight off the action bar. Colours match the bar's own.
		if (elements.health) {
			bar.health().ifPresent(health -> content.put("health", List.of(labelled(
					"HP", overMax(health, bar.maxHealth(), shortForm), ChatFormatting.RED))));
		}

		if (elements.defense) {
			bar.defense().ifPresent(defense -> content.put("defense", List.of(labelled(
					"Defense", Numbers.format(defense, shortForm), ChatFormatting.GREEN))));
		}

		if (elements.mana) {
			bar.mana().ifPresent(mana -> content.put("mana", List.of(labelled(
					"Mana", overMax(mana, bar.maxMana(), shortForm), ChatFormatting.AQUA))));
		}

		if (elements.location) {
			state.location().ifPresent(location -> {
				List<Component> lines = new ArrayList<>(1);
				addWrapped(lines, location, ChatFormatting.GREEN);
				content.put("location", List.copyOf(lines));
			});
		}

		if (elements.time) {
			state.time().ifPresent(time -> content.put("time",
					List.of(Component.literal(time).withStyle(ChatFormatting.YELLOW))));
		}

		if (elements.date) {
			state.date().ifPresent(date -> content.put("date",
					List.of(Component.literal(date).withStyle(ChatFormatting.YELLOW))));
		}

		if (elements.purse) {
			state.purse().ifPresent(value -> content.put("purse", List.of(
					labelled("Purse", Numbers.format(value, shortForm), ChatFormatting.GOLD))));
		}

		if (elements.bits) {
			state.bits().ifPresent(value -> content.put("bits", List.of(
					labelled("Bits", Numbers.format(value, shortForm), ChatFormatting.AQUA))));
		}

		// Each session readout is its own element so it can be toggled and placed
		// independently. "gained" in the labels and a mandatory sign: the purse and
		// bits lines show balances, and a bare "Coins"/"Bits" here read as
		// duplicates of them rather than as this session's change.
		if (elements.session && session.started()) {
			content.put("session", List.of(
					labelled("Session", session.formattedDuration(), ChatFormatting.YELLOW)));
		}

		if (elements.coinsGained) {
			session.coinsGained().ifPresent(gained -> content.put("coinsgained", List.of(
					labelled("Coins gained", signed(gained, shortForm), ChatFormatting.GOLD))));
		}

		if (elements.coinsPerHour) {
			session.coinsPerHour().ifPresent(rate -> content.put("coinshour", List.of(
					labelled("Coins/h", Numbers.format(rate, shortForm), ChatFormatting.GOLD))));
		}

		if (elements.bitsGained) {
			// Only once bits have actually moved; a standing "+0" would be noise.
			session.bitsGained().ifPresent(gained -> {
				if (gained != 0) {
					content.put("bitsgained", List.of(labelled(
							"Bits gained", signed(gained, shortForm), ChatFormatting.AQUA)));
				}
			});
		}

		if (elements.objective) {
			List<Component> objective = new ArrayList<>(2);
			state.objective().ifPresent(quest ->
					addWrapped(objective, quest, ChatFormatting.YELLOW));
			state.objectiveStatus().ifPresent(status ->
					addWrapped(objective, status, ChatFormatting.GRAY));

			if (!objective.isEmpty()) {
				content.put("objective", List.copyOf(objective));
			}
		}

		if (elements.slayer) {
			List<Component> slayer = new ArrayList<>(3);
			state.slayerQuest().ifPresent(quest ->
					addWrapped(slayer, quest, ChatFormatting.RED));
			state.slayerStatus().ifPresent(status ->
					addWrapped(slayer, status, ChatFormatting.GREEN));

			// The stopwatch: live while the boss is up, then how long it took.
			var timer = dev.skyaid.core.SlayerTracker.timer();
			long now = System.currentTimeMillis();
			timer.bossUpFor(now).ifPresentOrElse(
					up -> addWrapped(slayer, "Boss up: "
							+ dev.skyaid.parse.SlayerTimer.format(up), ChatFormatting.AQUA),
					() -> timer.lastKill().ifPresent(kill ->
							addWrapped(slayer, "Last kill: "
											+ dev.skyaid.parse.SlayerTimer.format(kill),
									ChatFormatting.GRAY)));

			if (!slayer.isEmpty()) {
				content.put("slayer", List.copyOf(slayer));
			}
		}

		List<String> leftover = state.extraLines();

		if (elements.events) {
			// Event countdowns get their own element; when it is off they fall
			// back into Other lines, so a timer is never silently lost.
			List<Component> timers = new ArrayList<>(2);
			List<String> rest = new ArrayList<>(leftover.size());

			for (String extra : leftover) {
				if (EventTimers.isTimer(extra)) {
					addWrapped(timers, extra, ChatFormatting.YELLOW);
				} else {
					rest.add(extra);
				}
			}

			// The mayor leads the events block: it is the one always-on
			// "event" and comes from Hypixel's keyless election resource.
			dev.skyaid.core.MayorTracker.mayor().ifPresent(mayor ->
					timers.add(0, Component.literal("Mayor: ")
							.withStyle(ChatFormatting.GRAY)
							.append(Component.literal(mayor)
									.withStyle(ChatFormatting.YELLOW))));

			if (!timers.isEmpty()) {
				content.put("events", List.copyOf(timers));
			}

			leftover = rest;
		}

		if (elements.commissions) {
			// Dwarven commission progress, claimed only in the mining zones so
			// the shape test cannot mistake percent lines elsewhere.
			boolean mining = state.location().map(zone ->
					zone.startsWith("Dwarven Mines") || zone.startsWith("Crystal Hollows")
							|| zone.startsWith("Mineshaft")).orElse(false);

			if (mining) {
				List<Component> commissions = new ArrayList<>(4);
				List<String> rest = new ArrayList<>(leftover.size());

				for (String extra : leftover) {
					if (dev.skyaid.parse.CommissionLines.isCommission(extra)) {
						addWrapped(commissions, extra, ChatFormatting.GOLD);
					} else {
						rest.add(extra);
					}
				}

				if (!commissions.isEmpty()) {
					content.put("commissions", List.copyOf(commissions));
				}

				leftover = rest;
			}
		}

		{
			// Every Garden line is its own HUD module: each
			// widget routes to a per-line id the Arrange screen can move or
			// drop alone; "garden" keeps only the lines without a module of
			// their own (Copper, Sowdust, plots, contests). Lines still come
			// from the sidebar and the tab list (dump-verified 2026-08-25).
			boolean garden = state.location().map(zone ->
					zone.contains("Garden") || zone.startsWith("Plot")).orElse(false);

			if (garden) {
				GardenLineBuckets buckets = new GardenLineBuckets();
				List<String> rest = new ArrayList<>(leftover.size());

				for (String extra : leftover) {
					if (dev.skyaid.parse.GardenLines.isGardenLine(extra)) {
						buckets.route(extra);
					} else {
						rest.add(extra);
					}
				}

				int fromTab = 0;
				boolean inMilestones = false;

				for (String raw : dev.skyaid.core.TabListReader.lines()) {
					String entry = dev.skyaid.parse.GardenLines.stripIcons(
							dev.skyaid.parse.FormatCodes.strip(raw)).trim();

					if (entry.startsWith("Crop Milestones")) {
						inMilestones = true;
						continue;
					}

					if (inMilestones) {
						if (entry.isEmpty() || entry.endsWith(":")) {
							inMilestones = false;
						} else if (fromTab < 6) {
							fromTab++;
							buckets.milestones.add(stat("Milestone: " + entry,
									ChatFormatting.YELLOW));
							continue;
						}
					}

					// A bare header ("Pests:") carries nothing - skip it.
					if (fromTab < 6 && !entry.endsWith(":")
							&& dev.skyaid.parse.GardenLines.isTabStat(entry)) {
						fromTab++;
						buckets.route(entry);
					}
				}

				buckets.put(content, elements);

				if (elements.croprate) {
					dev.skyaid.core.CropRateTracker.cropsPerMinute().ifPresent(
							rate -> content.put("croprate", List.of(
									labelled("Crops", rate, ChatFormatting.GREEN))));
				}

				var player = net.minecraft.client.Minecraft.getInstance().player;

				if (elements.align && player != null) {
					content.put("align", List.of(labelled("Align",
							dev.skyaid.parse.GardenLines.angle(
									player.getYRot(), player.getXRot()),
							ChatFormatting.DARK_GREEN)));
				}

				leftover = rest;
			}
		}

		// The tab list's Info blocks: bank, skill, speed, gems, and the pet
		// with its XP line (all dump-verified shapes 2026-08-25). One walk
		// serves every element that reads it.
		if (elements.bank || elements.skill || elements.speed || elements.gems
				|| elements.pet || elements.skillrate) {
			boolean skillNext = false;
			int petNext = 0;
			List<String> petLines = new ArrayList<>(2);

			for (String raw : dev.skyaid.core.TabListReader.lines()) {
				String entry = dev.skyaid.parse.GardenLines.stripIcons(
						dev.skyaid.parse.FormatCodes.strip(raw)).trim();

				if (entry.isEmpty()) {
					petNext = 0;
					continue;
				}

				if (skillNext) {
					skillNext = false;
					dev.skyaid.core.SkillRateTracker.observe(entry);

					if (elements.skill && !content.containsKey("skill")) {
						content.put("skill", List.of(stat("Skill: " + entry,
								ChatFormatting.AQUA)));
					}
				}

				if (petNext > 0 && petLines.size() < 2) {
					petLines.add(entry);
					petNext--;
					continue;
				}

				if (entry.startsWith("Skills:")) {
					skillNext = true;
				} else if (entry.equals("Pet:")) {
					petNext = 2;
				} else if (elements.bank && entry.startsWith("Bank: ")
						&& !content.containsKey("bank")) {
					content.put("bank", List.of(bankLine(
							entry.substring("Bank: ".length()))));
				} else if (elements.speed && entry.startsWith("Speed: ")
						&& !content.containsKey("speed")) {
					content.put("speed", List.of(stat(entry, ChatFormatting.WHITE)));
				} else if (elements.gems && entry.startsWith("Gems: ")
						&& !content.containsKey("gems")) {
					content.put("gems", List.of(stat(entry, ChatFormatting.GREEN)));
				}
			}

			if (elements.skillrate) {
				dev.skyaid.core.SkillRateTracker.perHour().ifPresent(rate ->
						content.put("skillrate", List.of(
								labelled("Skill/hr", rate, ChatFormatting.AQUA))));
			}

			if (elements.pet) {
				if (!petLines.isEmpty()) {
					// Two short lines beat one long one: the pet's name and
					// its XP progress each get their own.
					List<Component> pet = new ArrayList<>(2);
					pet.add(labelled("Pet", petLines.get(0), ChatFormatting.AQUA));

					if (petLines.size() > 1) {
						pet.add(Component.literal("  " + petLines.get(1))
								.withStyle(ChatFormatting.GRAY));
					}

					content.put("pet", pet);
				} else {
					// No tab pet block: the chat-based tracker still knows
					// the name from the summon line.
					dev.skyaid.core.PetTracker.current().ifPresent(pet ->
							content.put("pet", List.of(labelled("Pet", pet,
									ChatFormatting.AQUA))));
				}
			}
		}

		if (elements.museum) {
			dev.skyaid.feature.MuseumTracker.progress().ifPresent(counts ->
					content.put("museum", List.of(labelled("Museum",
							counts[0] + "/" + counts[1] + " donated",
							ChatFormatting.LIGHT_PURPLE))));
		}

		if (elements.jacob) {
			dev.skyaid.feature.JacobContests.hudLine().ifPresent(line ->
					content.put("jacob", List.of(line)));
		}

		if (elements.cooldown) {
			dev.skyaid.feature.CooldownTracker.hudLine().ifPresent(line ->
					content.put("cooldown", List.of(line)));
		}

		// Mining widgets off the tab list: the powder balances under their
		// "Powders" header, plus any Heart of the Mountain line. Wordings
		// are ecosystem knowledge; a miss leaves the element absent.
		if (elements.powder) {
			List<Component> powder = new ArrayList<>(4);
			boolean inPowders = false;

			for (String raw : dev.skyaid.core.TabListReader.lines()) {
				String entry = dev.skyaid.parse.GardenLines.stripIcons(
						dev.skyaid.parse.FormatCodes.strip(raw)).trim();

				if (entry.startsWith("Powders") || entry.startsWith("Powder:")) {
					inPowders = true;
					continue;
				}

				if (inPowders) {
					if (entry.isEmpty() || entry.endsWith(":")
							|| powder.size() >= 3) {
						inPowders = false;
					} else {
						powder.add(stat(entry, ChatFormatting.LIGHT_PURPLE));
						continue;
					}
				}

				if (entry.startsWith("Heart of the Mountain")
						&& !entry.endsWith(":")) {
					powder.add(stat(entry, ChatFormatting.GOLD));
				}
			}

			if (!powder.isEmpty()) {
				content.put("powder", List.copyOf(powder));
			}
		}

		if (elements.nucleus) {
			dev.skyaid.feature.NucleusRuns.hudLine().ifPresent(line ->
					content.put("nucleus", List.of(line)));
		}

		if (elements.gemstones) {
			dev.skyaid.feature.GemstoneSession.hudLine().ifPresent(line ->
					content.put("gemstones", List.of(line)));
		}

		// Only present once something dropped - an eternal zero is clutter.
		if (elements.drops && dev.skyaid.core.DropTracker.count() > 0) {
			List<Component> drops = new ArrayList<>(2);
			drops.add(labelled("Drops", dev.skyaid.core.DropTracker.count()
					+ " this session", ChatFormatting.LIGHT_PURPLE));
			dev.skyaid.core.DropTracker.last().ifPresent(last ->
					drops.add(Component.literal("  Last: " + last)
							.withStyle(ChatFormatting.GRAY)));
			content.put("drops", drops);
		}

		if (elements.fishing
				&& dev.skyaid.feature.FishingAlerts.sessionCreatures() > 0) {
			List<Component> fishing = new ArrayList<>(2);
			fishing.add(labelled("Sea creatures",
					dev.skyaid.feature.FishingAlerts.sessionCreatures()
							+ " this session", ChatFormatting.AQUA));
			dev.skyaid.feature.FishingAlerts.lastCreature().ifPresent(last ->
					fishing.add(Component.literal("  Last: " + last)
							.withStyle(ChatFormatting.GRAY)));
			content.put("fishing", fishing);
		}

		if (elements.dungeon) {
			// The dungeon progress lines, ahead of the party split below.
			List<Component> stats = new ArrayList<>(4);
			List<String> rest = new ArrayList<>(leftover.size());

			// The identified room and its secrets progress lead the block -
			// the piece of dungeon state SkyAid itself knows best.
			dev.skyaid.dungeon.core.SecretsBoard.hudLine().ifPresent(line ->
					addWrapped(stats, line, ChatFormatting.AQUA));

			java.util.OptionalLong clearPercent = java.util.OptionalLong.empty();

			for (String extra : leftover) {
				if (DungeonLines.isDungeonStat(extra)) {
					if (extra.startsWith("Cleared:")) {
						clearPercent = firstNumber(extra);
					}

					addWrapped(stats, extra, ChatFormatting.WHITE);
				} else {
					rest.add(extra);
				}
			}

			// The boss fight clock, from the first "[BOSS]" dialogue line;
			// once a speaker is known, the phase and ITS clock ride along.
			long bossStart = dev.skyaid.dungeon.core.DungeonTracker.bossStartMillis();

			if (bossStart > 0 && ConfigManager.get().bossClock) {
				String phase = dev.skyaid.dungeon.core.DungeonTracker.bossPhase()
						.map(name -> "  " + name + " " + dev.skyaid.parse.SlayerTimer.format(
								System.currentTimeMillis() - dev.skyaid.dungeon.core
										.DungeonTracker.phaseStartMillis()))
						.orElse("");
				addWrapped(stats, "Boss: " + dev.skyaid.parse.SlayerTimer.format(
						System.currentTimeMillis() - bossStart) + phase, ChatFormatting.RED);
			}

			// The estimated run score, from the tab list's run state. Shown
			// only once the tab is actually reporting a dungeon.
			if (state.inCatacombs() && ConfigManager.get().dungeonScore) {
				var tab = cachedDungeonTab();

				if (tab.secretsPercent().isPresent() || !tab.puzzles().isEmpty()) {
					var score = dev.skyaid.parse.DungeonScore.estimate(tab, clearPercent);
					addWrapped(stats, "Score: ~" + score.total()
							+ " (" + score.grade() + ")", ChatFormatting.YELLOW);
				}
			}

			if (!stats.isEmpty()) {
				content.put("dungeon", List.copyOf(stats));
			}

			leftover = rest;
		}

		if (elements.party) {
			// The dungeon party members get their own element, health shortened.
			List<Component> party = new ArrayList<>(5);
			List<String> rest = new ArrayList<>(leftover.size());

			for (String extra : leftover) {
				if (DungeonLines.isPartyLine(extra)) {
					addWrapped(party, DungeonLines.withShortHealth(extra, shortForm),
							ChatFormatting.WHITE);
				} else {
					rest.add(extra);
				}
			}

			if (!party.isEmpty()) {
				content.put("party", List.copyOf(party));
			}

			leftover = rest;
		}

		if (elements.other && !leftover.isEmpty()) {
			// Whatever the parser could not place, verbatim. This is what keeps the
			// sidebar replacement honest in contexts nothing here understands. Left
			// plain, since there is no way to know what any of it means.
			List<Component> extras = new ArrayList<>(leftover.size());

			for (String extra : leftover) {
				addWrapped(extras, DungeonLines.withShortHealth(extra, shortForm),
						ChatFormatting.WHITE);
			}

			content.put("other", List.copyOf(extras));
		}

		return content;
	}

	/**
	 * Appends the text as one row when it fits, or word-wrapped onto further
	 * rows when it does not, all in the given colour. Only free-text lines go
	 * through this - the labelled numbers are bounded already.
	 */
	private static void addWrapped(List<Component> out, String text, ChatFormatting colour) {
		net.minecraft.client.gui.Font font =
				net.minecraft.client.Minecraft.getInstance().font;

		if (font.width(text) <= wrapWidth()) {
			out.add(Component.literal(text).withStyle(colour));
			return;
		}

		for (var line : font.getSplitter().splitLines(
				text, wrapWidth(), net.minecraft.network.chat.Style.EMPTY)) {
			out.add(Component.literal(line.getString().strip()).withStyle(colour));
		}
	}

	/** "745/745" when the maximum is known, else just the current value. */
	private static String overMax(long value, OptionalLong max, boolean shortForm) {
		String current = Numbers.format(value, shortForm);

		return max.isPresent()
				? current + "/" + Numbers.format(max.getAsLong(), shortForm)
				: current;
	}

	/** A gain with its sign always written: "+34.5k", "-600k". */
	private static String signed(long value, boolean shortForm) {
		return (value < 0 ? "" : "+") + Numbers.format(value, shortForm);
	}

	/**
	 * "21.3M / 0" from the tab list is coop / personal (dump-verified
	 * shape); shown split with a computed total. A plain value (no
	 * personal vault) stays a single Bank figure.
	 */
	private static Component bankLine(String value) {
		int slash = value.indexOf(" / ");

		if (slash < 0) {
			return labelled("Bank", value.trim(), ChatFormatting.GOLD);
		}

		String coop = value.substring(0, slash).trim();
		String personal = value.substring(slash + 3).trim();
		Component line = labelled("Coop", coop, ChatFormatting.GOLD)
				.copy().append(Component.literal("  "))
				.append(labelled("Personal", personal, ChatFormatting.GOLD));

		var coopAmount = dev.skyaid.parse.BankTransfers.parseAmount(coop);
		var personalAmount = dev.skyaid.parse.BankTransfers.parseAmount(personal);

		if (coopAmount.isPresent() && personalAmount.isPresent()) {
			line = line.copy().append(Component.literal("  "))
					.append(labelled("Total", Numbers.shorten(
							coopAmount.getAsLong() + personalAmount.getAsLong()),
							ChatFormatting.GOLD));
		}

		return line;
	}

	/** "Label: value" split into a dimmed label and a coloured value. */
	private static Component stat(String line, ChatFormatting valueColor) {
		int colon = line.indexOf(": ");

		if (colon <= 0) {
			return Component.literal(line).withStyle(valueColor);
		}

		return labelled(line.substring(0, colon),
				line.substring(colon + 2), valueColor);
	}

	/** A dimmed label with the value in its own colour, e.g. "Purse: 7.9M". */
	private static Component labelled(String label, String value, ChatFormatting valueColor) {
		return Component.literal(label + ": ").withStyle(LABEL)
				.append(Component.literal(value).withStyle(valueColor));
	}

	/**
	 * Sorts each Garden widget line into the per-line module it belongs to,
	 * keeping the colour it always had, then publishes only the buckets
	 * whose switch is on. One routing table for both sources - the sidebar
	 * and the tab list word the same stats the same way.
	 */
	private static final class GardenLineBuckets {
		final List<Component> misc = new ArrayList<>(4);
		final List<Component> level = new ArrayList<>(1);
		final List<Component> milestones = new ArrayList<>(2);
		final List<Component> fortunes = new ArrayList<>(3);
		final List<Component> pests = new ArrayList<>(2);
		final List<Component> visitors = new ArrayList<>(2);
		final List<Component> composter = new ArrayList<>(2);

		void route(String line) {
			if (line.startsWith("Garden Level")) {
				level.add(stat(line, ChatFormatting.AQUA));
			} else if (line.contains("Fortune: ")) {
				fortunes.add(stat(line, ChatFormatting.GOLD));
			} else if (line.startsWith("Pests") || line.startsWith("Bonus Pest")) {
				pests.add(stat(line, ChatFormatting.RED));
			} else if (line.startsWith("Visitors") || line.startsWith("Next Visitor")) {
				visitors.add(stat(line, ChatFormatting.GREEN));
			} else if (line.startsWith("Milestone")) {
				milestones.add(stat(line, ChatFormatting.YELLOW));
			} else if (line.startsWith("Composter")) {
				composter.add(stat(line, ChatFormatting.GREEN));
			} else {
				misc.add(stat(line, line.startsWith("Copper")
						? ChatFormatting.GOLD : ChatFormatting.GREEN));
			}
		}

		void put(java.util.Map<String, List<Component>> content,
				dev.skyaid.config.Config.HudElements elements) {
			putIf(content, elements, "garden", misc);
			putIf(content, elements, "gardenlevel", level);
			putIf(content, elements, "milestone", milestones);
			putIf(content, elements, "fortune", fortunes);
			putIf(content, elements, "pests", pests);
			putIf(content, elements, "visitors", visitors);
			putIf(content, elements, "composter", composter);
		}

		private static void putIf(java.util.Map<String, List<Component>> content,
				dev.skyaid.config.Config.HudElements elements,
				String id, List<Component> lines) {
			if (!lines.isEmpty() && elements.isShown(id)) {
				content.put(id, List.copyOf(lines));
			}
		}
	}
}
