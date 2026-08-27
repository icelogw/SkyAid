package dev.skyaid.config;

import dev.skyaid.api.HypixelApiClient;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.core.SessionTracker;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.feature.SidebarDump;
import dev.skyaid.hud.HudArrangeScreen;
import dev.skyaid.hud.HudPositionScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.function.Consumer;

/**
 * SkyAid's settings, grouped into tabs.
 *
 * <p>Built from vanilla widgets rather than a config library, which keeps Fabric
 * API as the mod's only hard dependency - a config library shipping late for a
 * new Minecraft version cannot then hold the mod back.
 *
 * <p>Uses the same tab bar as vanilla's own menus, so it looks native and has
 * room to grow: a new feature gets a row in whichever tab it belongs to, rather
 * than another button on one ever-lengthening list.
 *
 * <p>Every option carries a hover description, written as a short first line
 * saying what it does and any caveat on its own line below. Left as one prose
 * paragraph they wrapped into a block of text nobody reads.
 *
 * <p>Changes apply immediately and are written to disk on close.
 */
public class ConfigScreen extends Screen {
	private static final int ROW_HEIGHT = 20;
	private static final int TAB_BAR_HEIGHT = 24;

	/**
	 * Two half-width cells plus the 4px grid spacing come to exactly one full-width
	 * row, so single and paired rows share an edge instead of stepping in and out.
	 */
	private static final int HALF_WIDTH = 150;
	private static final int ROW_WIDTH = HALF_WIDTH * 2 + 4;

	private final Screen parent;

	private final TabManager tabManager =
			new TabManager(this::addRenderableWidget, this::removeWidget);

	/**
	 * Rebuilt on every init rather than held for the screen's lifetime. init runs
	 * again each time the screen is shown - including on returning from the key
	 * popup - and a layout kept across those calls would collect a second Done
	 * button every time.
	 */
	private HeaderAndFooterLayout layout;

	private MenuTabBar tabBar;

	public ConfigScreen(Screen parent) {
		super(Component.literal("SkyAid Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout = new HeaderAndFooterLayout(this);

		tabBar = addRenderableWidget(MenuTabBar.builder(tabManager, this.width)
				.addTabs(generalTab(), hudTab(), dungeonsTab(), alertsTab(), chatTab(),
						statsTab(), otherTab())
				.build());

		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.width(ROW_WIDTH)
				.build());

		layout.visitWidgets(widget -> addRenderableWidget(widget));
		tabBar.selectTab(0, false);
		repositionElements();
	}

	private GridLayoutTab generalTab() {
		return tab("General", grid -> {
			Config config = ConfigManager.get();

			row(grid, 0, tip(CycleButton.onOffBuilder(config.enabled)
							.create(0, 0, ROW_WIDTH, ROW_HEIGHT,
									Component.literal("SkyAid enabled"),
									(button, value) -> config.enabled = value),
					"Master switch for the mod.",
					"",
					"Turns off the HUD, chat",
					"filtering and the sidebar",
					"replacement at once."));

			row(grid, 1, tip(CycleButton.onOffBuilder(config.fairySouls)
							.create(0, 0, ROW_WIDTH, ROW_HEIGHT,
									Component.literal("Fairy soul markers"),
									(button, value) -> config.fairySouls = value),
					"Pink boxes on unclaimed",
					"fairy souls near you, from",
					"community locations.",
					"",
					"Souls you collect are",
					"remembered and stop",
					"showing."));

			row(grid, 2, tip(new WaypointRangeSlider(0, 0, ROW_WIDTH, ROW_HEIGHT),
					"How far away secret markers,",
					"fairy souls and waypoint",
					"beacons stay visible, in",
					"blocks - the far end of the",
					"slider is No limit.",
					"",
					"Beacons also hide when you",
					"are nearly on top of them,",
					"and vanish on arrival."));

			row(grid, 3, tip(CycleButton.onOffBuilder(config.priceTooltips)
							.create(0, 0, ROW_WIDTH, ROW_HEIGHT,
									Component.literal("Price tooltips"),
									(button, value) -> config.priceTooltips = value),
					"Adds the bazaar buy and sell",
					"price to item tooltips.",
					"",
					"Prices refresh every minute;",
					"items the bazaar does not",
					"trade show nothing extra."));

			row(grid, 4, tip(CycleButton.onOffBuilder(config.tooltipScroll)
							.create(0, 0, ROW_WIDTH, ROW_HEIGHT,
									Component.literal("Tooltip scrolling"),
									(button, value) -> config.tooltipScroll = value),
					"Tooltips taller than the",
					"screen scroll with the",
					"mouse wheel instead of",
					"being cut off.",
					"",
					"Starts at the top; scroll",
					"down to read the rest."));

			row(grid, 5, tip(CycleButton.onOffBuilder(config.pestHighlight)
							.create(0, 0, ROW_WIDTH, ROW_HEIGHT,
									Component.literal("Pest highlight"),
									(button, value) -> config.pestHighlight = value),
					"Glowing boxes on Garden",
					"pests so they are findable",
					"across the plots.",
					"",
					"Pest names are best-effort",
					"until verified in game."));

			row(grid, 6, tip(CycleButton.onOffBuilder(config.autoResourcePack)
							.create(0, 0, ROW_WIDTH, ROW_HEIGHT,
									Component.literal("Auto resource pack"),
									(button, value) -> config.autoResourcePack = value),
					"Accepts Hypixel's required",
					"resource pack without the",
					"prompt every join.",
					"",
					"Same as setting the server",
					"to 'Resource Packs: Enabled'.",
					"",
					"For security it only applies",
					"to Hypixel - every other",
					"server still asks first."));

			row(grid, 8, tip(CycleButton.onOffBuilder(config.mouseLockEnabled)
							.create(0, 0, ROW_WIDTH, ROW_HEIGHT,
									Component.literal("Mouse lock"),
									(button, value) -> config.mouseLockEnabled = value),
					"Master switch for the",
					"hold-to-lock camera keys.",
					"",
					"Locks only while a key is",
					"physically held; ships off",
					"so a frozen camera is",
					"never a surprise."));

			row(grid, 9, tip(Button.builder(Component.literal("Mouse lock presets..."),
							button -> this.minecraft.setScreenAndShow(
									new dev.skyaid.feature.MouseLockScreen()))
					.bounds(0, 0, ROW_WIDTH, ROW_HEIGHT)
					.build(),
					"Preset groups and the six",
					"hold-key angles - the same",
					"menu as /skyaid mouselock."));

			row(grid, 7, tip(CycleButton.onOffBuilder(config.menuReplace)
							.create(0, 0, ROW_WIDTH, ROW_HEIGHT,
									Component.literal("SkyBlock menu extras"),
									(button, value) -> config.menuReplace = value),
					"Adds SkyAid's warp items to",
					"the real SkyBlock Menu's",
					"empty row.",
					"",
					"The menu itself stays",
					"Hypixel's own."));
		});
	}

	/** Kept as fields so {@link #tick()} can refresh them while the screen is open. */
	private StringWidget serverStatus;
	private StringWidget sessionStatus;
	private StringWidget keyStatus;

	/** The armed-reset window: a second click inside it wipes the ledger. */
	private static final long RESET_CONFIRM_MILLIS = 5_000;
	private Button resetLifetimeButton;
	private long resetArmedAt;

	/** The all-time ledger rows, refreshed live like the status readout. */
	private StringWidget lifetimeSince;
	private StringWidget lifetimeTime;
	private StringWidget lifetimeCoins;
	private StringWidget lifetimeBits;
	private StringWidget lifetimeSessions;
	private StringWidget lifetimeDungeons;
	private StringWidget lifetimeHunts;
	private StringWidget lifetimeBlocks;
	private StringWidget lifetimeFinds;
	private StringWidget lifetimeGarden;
	private StringWidget lifetimeMuseum;

	/**
	 * All-time statistics: the ledger the session counters feed and game
	 * closes never clear. Numbers, not switches - plus the one reset.
	 */
	private GridLayoutTab statsTab() {
		return tab("Stats", grid -> {
			lifetimeSince = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);
			lifetimeTime = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);
			lifetimeCoins = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);
			lifetimeBits = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);
			lifetimeSessions = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);
			lifetimeDungeons = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);
			lifetimeHunts = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);
			lifetimeBlocks = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);
			lifetimeFinds = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);
			lifetimeGarden = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);
			lifetimeMuseum = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);

			// One section per row, records beside the total they belong to:
			// active time with the longest session, coins with the best.
			row(grid, 0, lifetimeSince);
			row(grid, 1, lifetimeTime);
			row(grid, 2, lifetimeCoins);
			row(grid, 3, lifetimeBits);
			row(grid, 4, lifetimeSessions);
			row(grid, 5, lifetimeDungeons);
			row(grid, 6, lifetimeHunts);
			row(grid, 7, lifetimeBlocks);
			row(grid, 8, lifetimeFinds);
			row(grid, 9, lifetimeGarden);
			row(grid, 10, lifetimeMuseum);
			refreshLifetime();
		});
	}

	private void refreshLifetime() {
		if (lifetimeSince == null) {
			return;
		}

		dev.skyaid.core.LifetimeStats.checkpoint();

		String date = java.time.Instant
				.ofEpochMilli(dev.skyaid.core.LifetimeStats.sinceMillis())
				.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
				.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"));

		lifetimeSince.setMessage(Component.literal("Tracking since " + date)
				.withStyle(ChatFormatting.GRAY));
		lifetimeTime.setMessage(Component.literal("Active time: ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(dev.skyaid.parse.TimeSpans.brief(
								dev.skyaid.core.LifetimeStats.activeMillis()))
						.withStyle(ChatFormatting.WHITE))
				.append(Component.literal("   longest session: ")
						.withStyle(ChatFormatting.GRAY))
				.append(Component.literal(dev.skyaid.parse.TimeSpans.brief(
								dev.skyaid.core.LifetimeStats.longestSessionMillis()))
						.withStyle(ChatFormatting.WHITE)));
		long activeHoursTenths = dev.skyaid.core.LifetimeStats.activeMillis() / 360_000;
		String perHour = activeHoursTenths < 10 ? "-" : dev.skyaid.parse.Numbers.shorten(
				Math.round(dev.skyaid.core.LifetimeStats.coins()
						/ (activeHoursTenths / 10.0)));

		lifetimeCoins.setMessage(Component.literal("Coins gained (net): ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(dev.skyaid.parse.Numbers.group(
								dev.skyaid.core.LifetimeStats.coins()))
						.withStyle(ChatFormatting.GOLD))
				.append(Component.literal("   best session: ")
						.withStyle(ChatFormatting.GRAY))
				.append(Component.literal(dev.skyaid.parse.Numbers.group(
								dev.skyaid.core.LifetimeStats.bestSessionCoins()))
						.withStyle(ChatFormatting.GOLD))
				.append(Component.literal("   avg/hr: ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(perHour).withStyle(ChatFormatting.GOLD)));
		lifetimeBits.setMessage(Component.literal("Bits gained: ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(dev.skyaid.parse.Numbers.group(
								dev.skyaid.core.LifetimeStats.bits()))
						.withStyle(ChatFormatting.AQUA)));
		lifetimeSessions.setMessage(Component.literal("Sessions: ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(String.valueOf(
								dev.skyaid.core.LifetimeStats.sessions()))
						.withStyle(ChatFormatting.WHITE)));
		lifetimeDungeons.setMessage(Component.literal("Dungeon runs: ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(String.valueOf(
								dev.skyaid.core.LifetimeStats.dungeonRuns()))
						.withStyle(ChatFormatting.RED))
				.append(Component.literal("   boss fights: ")
						.withStyle(ChatFormatting.GRAY))
				.append(Component.literal(String.valueOf(
								dev.skyaid.core.LifetimeStats.bossFights()))
						.withStyle(ChatFormatting.RED))
				.append(Component.literal("   slayers: ")
						.withStyle(ChatFormatting.GRAY))
				.append(Component.literal(String.valueOf(
								dev.skyaid.core.LifetimeStats.slayerBosses()))
						.withStyle(ChatFormatting.LIGHT_PURPLE)));
		lifetimeHunts.setMessage(Component.literal("Sea creatures: ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(String.valueOf(
								dev.skyaid.core.LifetimeStats.seaCreatures()))
						.withStyle(ChatFormatting.AQUA)));
		lifetimeBlocks.setMessage(Component.literal("Blocks mined: ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(dev.skyaid.parse.Numbers.group(
								dev.skyaid.core.LifetimeStats.blocksMined()))
						.withStyle(ChatFormatting.GREEN))
				.append(Component.literal("   deaths: ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(String.valueOf(
								dev.skyaid.core.LifetimeStats.deaths()))
						.withStyle(ChatFormatting.RED)));
		lifetimeFinds.setMessage(Component.literal("Fairy souls: ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(String.valueOf(
								dev.skyaid.core.LifetimeStats.fairySouls()))
						.withStyle(ChatFormatting.LIGHT_PURPLE))
				.append(Component.literal("   rare drops: ")
						.withStyle(ChatFormatting.GRAY))
				.append(Component.literal(String.valueOf(
								dev.skyaid.core.LifetimeStats.rareDrops()))
						.withStyle(ChatFormatting.GOLD))
				.append(Component.literal("   nucleus runs: ")
						.withStyle(ChatFormatting.GRAY))
				.append(Component.literal(String.valueOf(
								dev.skyaid.core.LifetimeStats.nucleusRuns()))
						.withStyle(ChatFormatting.AQUA)));
		lifetimeGarden.setMessage(Component.literal("Visitors served: ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(String.valueOf(
								dev.skyaid.feature.VisitorLedger.lifetimeServed()))
						.withStyle(ChatFormatting.GREEN))
				.append(Component.literal("   ~spent on them: ")
						.withStyle(ChatFormatting.GRAY))
				.append(Component.literal(dev.skyaid.parse.Numbers.shorten(
								dev.skyaid.feature.VisitorLedger.lifetimeSpent()))
						.withStyle(ChatFormatting.GOLD)));
		lifetimeMuseum.setMessage(dev.skyaid.feature.MuseumTracker.progress()
				.map(counts -> (Component) Component.literal("Museum: ")
						.withStyle(ChatFormatting.GRAY)
						.append(Component.literal(counts[0] + "/" + counts[1] + " donated")
								.withStyle(ChatFormatting.LIGHT_PURPLE)))
				.orElseGet(() -> Component.literal(
								"Museum: run /skyaid museum once to sync")
						.withStyle(ChatFormatting.DARK_GRAY)));
	}

	@Override
	public void tick() {
		refreshStatus();
		refreshLifetime();

		// An armed reset that was left alone quietly stands down.
		if (resetArmedAt > 0 && System.currentTimeMillis()
				- resetArmedAt > RESET_CONFIRM_MILLIS && resetLifetimeButton != null) {
			resetArmedAt = 0;
			resetLifetimeButton.setMessage(Component.literal("Reset all-time stats"));
		}
	}

	private void refreshStatus() {
		if (serverStatus == null) {
			return;
		}

		serverStatus.setMessage(serverLine());
		sessionStatus.setMessage(sessionLine());
		keyStatus.setMessage(Component.literal(
						HypixelApiClient.hasApiKey() ? "API key: set" : "API key: not set")
				.withStyle(HypixelApiClient.hasApiKey()
						? ChatFormatting.GREEN : ChatFormatting.GRAY));
	}

	private static Component serverLine() {
		if (!HypixelDetector.isOnHypixel()) {
			return Component.literal("Server: not on Hypixel")
					.withStyle(ChatFormatting.GRAY);
		}

		return SkyblockTracker.state().inSkyblock()
				? Component.literal("Server: Hypixel - in Skyblock")
						.withStyle(ChatFormatting.GREEN)
				: Component.literal("Server: Hypixel - not in Skyblock")
						.withStyle(ChatFormatting.YELLOW);
	}

	private static Component sessionLine() {
		var session = SessionTracker.snapshot();

		return session.started()
				? Component.literal("Session: " + session.formattedDuration() + " in Skyblock")
						.withStyle(ChatFormatting.WHITE)
				: Component.literal("Session: not started yet")
						.withStyle(ChatFormatting.GRAY);
	}

	private static String version() {
		return net.fabricmc.loader.api.FabricLoader.getInstance()
				.getModContainer(dev.skyaid.SkyAidClient.MOD_ID)
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElse("");
	}

	private GridLayoutTab hudTab() {
		return tab("HUD", grid -> {
			Config config = ConfigManager.get();

			wide(grid, 0, tip(CycleButton.onOffBuilder(config.skyblockHud.visible)
							.create(0, 0, ROW_WIDTH, ROW_HEIGHT,
									Component.literal("Skyblock HUD"),
									(button, value) -> config.skyblockHud.visible = value),
					"The readout of location,",
					"time, purse and more.",
					"",
					"Only on Hypixel Skyblock."));

			wide(grid, 1, tip(CycleButton.onOffBuilder(config.skyblockHud.hideHypixelSidebar)
							.create(0, 0, ROW_WIDTH, ROW_HEIGHT,
									Component.literal("Hide Hypixel sidebar"),
									(button, value) ->
											config.skyblockHud.hideHypixelSidebar = value),
					"Hides Hypixel's sidebar so",
					"it does not double up.",
					"",
					"Only on Hypixel Skyblock,",
					"with the HUD on. Sections",
					"SkyAid cannot read yet -",
					"dungeons, mining, events -",
					"would be hidden too."));

			half(grid, 2, 0, tip(Button.builder(Component.literal("Move HUD..."),
									button -> this.minecraft.setScreenAndShow(
											new HudPositionScreen(this)))
							.bounds(0, 0, HALF_WIDTH, ROW_HEIGHT)
							.build(),
					"Drag the readout to move it.",
					"Also resizes or resets it."));

			half(grid, 2, 1, tip(Button.builder(Component.literal("Arrange..."),
									button -> this.minecraft.setScreenAndShow(
											new HudArrangeScreen(this)))
							.bounds(0, 0, HALF_WIDTH, ROW_HEIGHT)
							.build(),
					"Reorder the lines and",
					"add dividers.",
					"",
					"A divider only shows where",
					"it still separates two",
					"visible lines."));

			half(grid, 3, 0, toggle("Background", config.skyblockHud.background,
					value -> config.skyblockHud.background = value,
					"Draws a dark panel behind",
					"the readout.",
					"",
					"Without it the text is hard",
					"to read against a bright",
					"sky or snow."));

			half(grid, 3, 1, toggle("Short numbers", config.skyblockHud.shortNumbers,
					value -> config.skyblockHud.shortNumbers = value,
					"Abbreviate large numbers.",
					"",
					"   10,000  ->  10k",
					"   7,884,267  ->  7.9M",
					"",
					"Quicker to read, but",
					"no longer exact."));

			// Tooltip lines are kept under Minecraft's ~30-character wrap width,
			// or it re-wraps them mid-thought and the layout falls apart.
			half(grid, 4, 1, toggle("Hide banner bars", config.skyblockHud.hideBannerBossBars,
					value -> config.skyblockHud.hideBannerBossBars = value,
					"Hides boss bars that are just",
					"text banners: hypixel.net",
					"adverts, and the Objective",
					"while the HUD shows it.",
					"",
					"Real boss fights still show",
					"their bar."));

			half(grid, 4, 0, tip(new CoinsRateWindowSlider(0, 0, HALF_WIDTH, ROW_HEIGHT),
					"How far back Coins/h averages.",
					"",
					"A short window shows what",
					"you are making right now.",
					"",
					"\"session\" averages the whole",
					"session, so quiet stretches",
					"slowly drag it down."));

			half(grid, 5, 0, tip(new HudOpacitySlider(0, 0, HALF_WIDTH, ROW_HEIGHT),
					"How dark the panel behind",
					"the readout is.",
					"",
					"0% hides it entirely, like",
					"turning Background off."));

			half(grid, 5, 1, tip(new HudWrapWidthSlider(0, 0, HALF_WIDTH, ROW_HEIGHT),
					"Where long lines wrap:",
					"objectives, commissions and",
					"other free text.",
					"",
					"Wider fits more per line,",
					"narrower keeps the panel",
					"slim."));

			half(grid, 6, 0, toggle("Text shadow", config.skyblockHud.textShadow,
					value -> config.skyblockHud.textShadow = value,
					"Drop shadow under the HUD",
					"text.",
					"",
					"Off reads cleaner on the",
					"dark panel; on helps with",
					"no background."));
		});
	}

	/** Everything Catacombs-specific in one place, as the dungeon suite grows. */
	private GridLayoutTab dungeonsTab() {
		return tab("Dungeons", grid -> {
			Config config = ConfigManager.get();

			half(grid, 0, 0, toggle("Dungeon map", config.dungeonMap.visible,
					value -> config.dungeonMap.visible = value,
					"Shows Hypixel's dungeon map",
					"on screen in the Catacombs.",
					"",
					"Move and resize it on the",
					"Move HUD screen. The same",
					"map as the Magical Map item,",
					"without holding it."));

			half(grid, 0, 1, toggle("Secret markers", config.secretMarkers,
					value -> config.secretMarkers = value,
					"Marks every secret in the",
					"room - chests, levers, bats,",
					"skulls, hidden walls - from",
					"the community room database.",
					"",
					"The nearest one is bright",
					"with a name tag; the rest",
					"stay faint until their turn."));

			half(grid, 1, 0, toggle("Puzzle solvers", config.puzzleSolvers,
					value -> config.puzzleSolvers = value,
					"Highlights puzzle solutions:",
					"blaze kill order, creeper",
					"beam pairs, and waterboard",
					"levers to flick.",
					"",
					"Shows only - it never",
					"clicks or moves for you."));

			half(grid, 1, 1, toggle("Waypoint beacons", config.waypointBeams,
					value -> config.waypointBeams = value,
					"Beacon beams for your",
					"/skyaid waypoint markers.",
					"",
					"Turning this off hides the",
					"beams without forgetting",
					"the waypoints."));

			half(grid, 2, 0, toggle("Terminal overlays", config.terminalOverlays,
					value -> config.terminalOverlays = value,
					"Washes the correct clicks",
					"green in the F7/M7 boss",
					"terminals: panes, order,",
					"colours, rubix counts.",
					"",
					"Shows only - every click",
					"stays yours."));

			half(grid, 2, 1, toggle("Chest value", config.chestValue,
					value -> config.chestValue = value,
					"Sums a reward chest's loot",
					"at bazaar or BIN value, one",
					"chat line per chest.",
					"",
					"Answers \"is this chest",
					"worth its price\" at a",
					"glance."));

			half(grid, 3, 0, toggle("Livid finder", config.lividFinder,
					value -> config.lividFinder = value,
					"Marks the REAL Livid on",
					"F5 and M5, read from the",
					"boss room's glass colour.",
					"",
					"The eight fakes stay",
					"unmarked."));

			half(grid, 3, 1, toggle("Score line", config.dungeonScore,
					value -> config.dungeonScore = value,
					"The estimated run score on",
					"the dungeon HUD element,",
					"like \"Score: ~285 (S)\".",
					"",
					"An estimate from the tab",
					"list - Hypixel keeps the",
					"exact maths to itself."));

			half(grid, 4, 0, toggle("Boss clock", config.bossClock,
					value -> config.bossClock = value,
					"Times the boss fight on the",
					"dungeon HUD element, with",
					"the current phase on F7.",
					"",
					"Starts at the boss's first",
					"dialogue line."));

			// The same slider as the General tab - dungeon markers are what
			// it caps, so it lives on both.
			half(grid, 4, 1, tip(
					new WaypointRangeSlider(0, 0, HALF_WIDTH, ROW_HEIGHT),
					"How far away secret markers,",
					"fairy souls and waypoint",
					"beacons stay visible,",
					"in blocks - or No limit.",
					"",
					"The same setting as on the",
					"General tab."));
		});
	}

	/** The watchers that speak up on their own - each one a single line. */
	private GridLayoutTab alertsTab() {
		return tab("Alerts", grid -> {
			Config config = ConfigManager.get();

			half(grid, 0, 0, toggle("Low HP warning", config.lowHpWarning,
					value -> config.lowHpWarning = value,
					"A red flash around the",
					"screen edge when health",
					"drops below a quarter.",
					"",
					"Shows only - nothing is",
					"done about it."));

			half(grid, 0, 1, toggle("Composter alert", config.composterAlert,
					value -> config.composterAlert = value,
					"One chat line when the",
					"Garden composter runs low",
					"on fuel or organic matter.",
					"",
					"Read from the tab widget;",
					"re-arms after a refill."));

			half(grid, 1, 0, toggle("Jacob alerts", config.jacobAlerts,
					value -> config.jacobAlerts = value,
					"A ping 5 minutes before a",
					"contest featuring a crop",
					"you watch.",
					"",
					"Watch crops with",
					"/skyaid jacob watch <crop>;",
					"the HUD line works without."));

			half(grid, 1, 1, toggle("Bazaar watchdog", config.bazaarWatchdog,
					value -> config.bazaarWatchdog = value,
					"After you open Your Bazaar",
					"Orders once, a chat line",
					"when an order is undercut",
					"or outbid.",
					"",
					"Checks the public bazaar",
					"API once a minute."));

			half(grid, 2, 0, toggle("Visitor ledger", config.visitorLedger,
					value -> config.visitorLedger = value,
					"Remembers what accepted",
					"Garden visitors cost at",
					"live prices.",
					"",
					"/skyaid visitors shows",
					"session and lifetime",
					"totals."));
		});
	}

	private GridLayoutTab chatTab() {
		return tab("Chat", grid -> {
			Config.ChatSettings settings = ConfigManager.get().chat;

			// Paired by purpose: what gets hidden on the left, what gets marked on
			// the right, with the repeat window beside the switch it belongs to.
			half(grid, 0, 0, toggle("Lobby join spam", settings.hideLobbyJoinSpam,
					value -> settings.hideLobbyJoinSpam = value,
					"Hides \"joined the lobby!\"",
					"announcements.",
					"",
					"Ordinary chat is untouched."));
			half(grid, 0, 1, toggle("Store adverts", settings.hidePromotions,
					value -> settings.hidePromotions = value,
					"Hides Hypixel's own store",
					"advertising.",
					"",
					"Matched on the store address,",
					"not on words like \"sale\" -",
					"so players talking about",
					"the auction house are not",
					"swallowed with it."));

			half(grid, 1, 0, toggle("Repeated messages", settings.hideDuplicateMessages,
					value -> settings.hideDuplicateMessages = value,
					"Hides a message identical",
					"to a recent one.",
					"",
					"Off by default - people",
					"do repeat themselves."));
			half(grid, 1, 1, tip(new DuplicateWindowSlider(0, 0, HALF_WIDTH, ROW_HEIGHT),
					"How far back the repeat",
					"filter looks.",
					"",
					"From 5 seconds to 5 minutes.",
					"Only applies while Repeated",
					"messages is on."));

			half(grid, 2, 0, toggle("Party/guild chat", settings.highlightPartyAndGuild,
					value -> settings.highlightPartyAndGuild = value,
					"Adds a coloured bar to",
					"party and guild lines.",
					"",
					"Hypixel's own rank colours",
					"are left alone."));
			half(grid, 2, 1, toggle("Mentions of you", settings.highlightMentions,
					value -> settings.highlightMentions = value,
					"Marks lines where somebody",
					"says your name.",
					"",
					"Only the message body counts,",
					"so your own messages do not",
					"flag themselves."));

			half(grid, 3, 0, mentionSoundRow(settings));
			half(grid, 3, 1, toggle("Timestamps", settings.timestamps,
					value -> settings.timestamps = value,
					"Prefixes each line with",
					"the time it arrived.",
					"",
					"Example: [14:32]"));

			half(grid, 5, 1, toggle("Auction alerts", settings.highlightAuctions,
					value -> settings.highlightAuctions = value,
					"Marks \"[Auction]\" announcements",
					"- sold, expired, outbid - and",
					"plays a high ping.",
					"",
					"They usually mean coins are",
					"waiting to be claimed."));

			half(grid, 6, 0, toggle("Chat waypoints", settings.chatWaypoints,
					value -> settings.chatWaypoints = value,
					"In the Catacombs, coordinates",
					"shared in party, guild or",
					"direct messages become beacon",
					"waypoints automatically.",
					"",
					"They fade after 3 minutes,",
					"or when you get there."));

			half(grid, 6, 1, toggle("Fishing alerts", settings.fishingAlerts,
					value -> settings.fishingAlerts = value,
					"When a notable sea creature",
					"spawns - Thunder, Jawbus,",
					"Yeti and friends - its name",
					"flashes on the action bar",
					"with a low ping.",
					"",
					"Double hooks ping too."));

			half(grid, 5, 0, toggle("Sack messages", settings.hideSackMessages,
					value -> settings.hideSackMessages = value,
					"Hides \"[Sacks] +240 items\"",
					"collection notices.",
					"",
					"They print every few seconds",
					"while farming or mining, and",
					"the sack shows its contents",
					"anyway."));

			half(grid, 4, 0, toggle("Cooldown spam", settings.hideAbilityCooldown,
					value -> settings.hideAbilityCooldown = value,
					"Hides \"This ability is on",
					"cooldown\" messages.",
					"",
					"They can fire several times",
					"a second in a fight, and the",
					"ability not firing already",
					"says the same thing."));

			// Directly under Timestamps, since it only means anything alongside it.
			half(grid, 4, 1, toggle("12-hour clock", settings.timestamps12Hour,
					value -> settings.timestamps12Hour = value,
					"Shows timestamps as 2:32pm",
					"rather than 14:32.",
					"",
					"Midnight reads 12:00am",
					"and noon 12:00pm."));
		});
	}
	private GridLayoutTab otherTab() {
		return tab("Other", grid -> {
			Config config = ConfigManager.get();

			// Key entry lives in its own popup, which has paste, validation and
			// masking - the key is never shown or typed on this screen.
			row(grid, 0, tip(Button.builder(Component.literal(keyButtonLabel()),
									button -> this.minecraft.setScreenAndShow(
											new ApiKeyScreen(this)))
							.bounds(0, 0, ROW_WIDTH, ROW_HEIGHT)
							.build(),
					"Only /skyaid stats needs a key.",
					"The HUD and chat features",
					"work without one.",
					"",
					"Opens a popup to paste it",
					"in, so the key never goes",
					"through chat."));

			row(grid, 1, tip(CycleButton.onOffBuilder(config.debug)
							.create(0, 0, ROW_WIDTH, ROW_HEIGHT,
									Component.literal("Debug mode"),
									(button, value) -> config.debug = value),
					"Shows diagnostic numbers on",
					"the Move HUD screen: the",
					"measured box, the cursor,",
					"and the screen size.",
					"",
					"Only useful when a control",
					"is not lining up."));

		row(grid, 2, tip(Button.builder(Component.literal("Save debug report"),
									button -> SidebarDump.dumpAndReport())
							.bounds(0, 0, ROW_WIDTH, ROW_HEIGHT)
							.build(),
					"Saves everything SkyAid can",
					"see right now - sidebar, tab",
					"list, open menu, entities -",
					"to a file in your game folder.",
					"",
					"If a readout looks wrong,",
					"this file is how it gets",
					"fixed. Same as /skyaid dump."));

			// Permanent deletion gets a confirmation: the first click arms,
			// the second within five seconds resets, waiting disarms.
			resetLifetimeButton = Button.builder(Component.literal("Reset all-time stats"),
							button -> {
								long now = System.currentTimeMillis();

								if (now - resetArmedAt > RESET_CONFIRM_MILLIS) {
									resetArmedAt = now;
									button.setMessage(Component.literal(
													"Are you sure? Click again")
											.withStyle(ChatFormatting.RED));
									return;
								}

								resetArmedAt = 0;
								dev.skyaid.core.LifetimeStats.resetAll();
								refreshLifetime();
								button.setMessage(Component.literal("Reset all-time stats"));
							})
					.bounds(0, 0, ROW_WIDTH, ROW_HEIGHT)
					.build();

			row(grid, 3, tip(Button.builder(Component.literal("Reset session counters"),
									button -> SessionTracker.reset())
							.bounds(0, 0, ROW_WIDTH, ROW_HEIGHT)
							.build(),
					"Starts session tracking over:",
					"time, coins and bits gained.",
					"",
					"Use it after switching",
					"profiles - a profile switch",
					"moves the purse, which reads",
					"as a huge gain or loss.",
					"",
					"Same as /skyaid session reset."));

			row(grid, 4, tip(resetLifetimeButton,
					"Starts the Stats tab's ledger",
					"over from zero, permanently.",
					"",
					"Asks once more before it",
					"actually resets. The HUD's",
					"session counters are",
					"separate and keep running."));

			// The live status readout, so "why is the HUD not showing" is
			// answered here rather than by guesswork. Refreshed from tick().
			serverStatus = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);
			sessionStatus = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);
			keyStatus = new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.empty(), this.font);

			row(grid, 5, serverStatus);
			row(grid, 6, sessionStatus);
			row(grid, 7, keyStatus);
			refreshStatus();

			row(grid, 8, new StringWidget(0, 0, ROW_WIDTH, ROW_HEIGHT,
					Component.literal("SkyAid " + version() + " - displays only, never acts")
							.withStyle(ChatFormatting.DARK_GRAY),
					this.font));
		});
	}

	/**
	 * The Mention sound switch with a small note button beside it that plays the
	 * sound once - the only way to know what to listen for without waiting to be
	 * mentioned. Playing a UI sound locally is display, not action.
	 */
	private static LinearLayout mentionSoundRow(Config.ChatSettings settings) {
		int previewWidth = 22;

		LinearLayout pair = new LinearLayout(
				HALF_WIDTH, ROW_HEIGHT, LinearLayout.Orientation.HORIZONTAL);
		pair.spacing(4);

		pair.addChild(tip(CycleButton.onOffBuilder(settings.mentionSound)
						.create(0, 0, HALF_WIDTH - previewWidth - 4, ROW_HEIGHT,
								Component.literal("Mention sound"),
								(button, value) -> settings.mentionSound = value),
				"Plays a short sound when",
				"somebody says your name.",
				"",
				"Hidden lines never ping."));

		// The same sound and route as the real mention ping in ChatCleanup, so what
		// this previews is exactly what a mention plays.
		pair.addChild(tip(Button.builder(
								Component.literal(String.valueOf((char) 0x266A)),
								button -> Minecraft.getInstance().getSoundManager().playDelayed(
										SimpleSoundInstance.forUI(
												SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f), 0))
						.bounds(0, 0, previewWidth, ROW_HEIGHT)
						.build(),
				"Plays the mention sound once,",
				"so you know what to",
				"listen for."));

		return pair;
	}

	/** An on/off switch at half width, for the two-column element rows. */
	private static CycleButton<Boolean> toggle(String label, boolean initial,
			Consumer<Boolean> onChange, String... description) {
		return tip(CycleButton.onOffBuilder(initial)
						.create(0, 0, HALF_WIDTH, ROW_HEIGHT, Component.literal(label),
								(button, value) -> onChange.accept(value)),
				description);
	}

	/**
	 * Attaches the hover description and hands the widget back, so calls stay
	 * inline.
	 *
	 * <p>Takes the description a line at a time rather than as one string. Minecraft
	 * wraps a long tooltip to its own width, which turned these into dense blocks
	 * broken at arbitrary points; controlling the breaks keeps the first line a
	 * readable summary and puts each caveat and example on its own row.
	 *
	 * <p>Keep every line at or under about 30 characters. Minecraft re-wraps
	 * anything longer mid-thought, and the hand-authored layout falls apart.
	 */
	private static <T extends AbstractWidget> T tip(T widget, String... lines) {
		widget.setTooltip(Tooltip.create(Component.literal(String.join("\n", lines))));
		return widget;
	}

	/**
	 * GridLayoutTab keeps its layout protected, so the grid is reached through a
	 * subclass rather than from outside.
	 */
	private static GridLayoutTab tab(String title, Consumer<GridLayout> rows) {
		return new GridLayoutTab(Component.literal(title)) {
			{
				this.layout.spacing(4);
				rows.accept(this.layout);
			}
		};
	}

	private static void row(GridLayout grid, int index, LayoutElement widget) {
		grid.addChild(widget, index, 0);
	}

	/** A full-width row, spanning both columns of a two-column tab. */
	private static void wide(GridLayout grid, int index, LayoutElement widget) {
		grid.addChild(widget, index, 0, 1, 2);
	}

	/** One half-width cell of a two-column row. */
	private static void half(GridLayout grid, int index, int column, LayoutElement widget) {
		grid.addChild(widget, index, column);
	}

	/** Shows whether a key is set without showing any part of it. */
	private static String keyButtonLabel() {
		return ConfigManager.get().hypixelApiKey.isBlank()
				? "Hypixel API key: not set"
				: "Hypixel API key: set";
	}

	@Override
	protected void repositionElements() {
		if (tabBar != null) {
			tabBar.arrangeElements(this.width);
		}

		if (layout == null) {
			return;
		}

		layout.setHeaderHeight(TAB_BAR_HEIGHT);
		layout.arrangeElements();
		tabManager.setTabArea(
				new ScreenRectangle(0, TAB_BAR_HEIGHT, this.width, layout.getContentHeight()));
	}

	@Override
	public void onClose() {
		ConfigManager.save();
		this.minecraft.setScreenAndShow(parent);
	}
}
