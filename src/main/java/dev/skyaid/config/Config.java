package dev.skyaid.config;

/**
 * User settings, serialised to config/skyaid.json by {@link ConfigManager}.
 *
 * <p>Plain mutable fields with defaults rather than a record: Gson fills these in
 * field by field, so a config written by an older version stays loadable and any
 * newly added field simply keeps its default.
 */
public class Config {
	/** Master switch. When false the mod renders and requests nothing. */
	public boolean enabled = true;

	/**
	 * Where the readout starts out: hard against the right edge, roughly a third
	 * down, which is where Hypixel's own sidebar sits - SkyAid replaces it out of
	 * the box. Values over 0.5 on x are right-aligned, so this grows leftwards from
	 * the edge and the x figure stays correct however wide the lines get.
	 */
	public static final float DEFAULT_HUD_X = 0.9965f;
	public static final float DEFAULT_HUD_Y = 0.3632f;

	public HudSettings skyblockHud = new HudSettings(DEFAULT_HUD_X, DEFAULT_HUD_Y);

	/**
	 * The dungeon map overlay: its own box in the top-right, deliberately not a
	 * line of the arrangeable readout. Only the visible/x/y/scale fields apply.
	 */
	public HudSettings dungeonMap = new HudSettings(0.9965f, 0.006f);

	public ChatSettings chat = new ChatSettings();

	/**
	 * Hypixel API key from developer.hypixel.net/dashboard.
	 *
	 * <p>Treated as a secret: never logged, never included in an error message, and
	 * only ever sent to api.hypixel.net. Empty means API-backed features stay off.
	 */
	public String hypixelApiKey = "";

	/** Set once the "a key is recommended" notice has been shown, so it never nags. */
	public boolean apiKeyNoticeDismissed = false;

	/**
	 * Draws the beacon beams for /skyaid waypoint markers. Turning it off hides
	 * the beams without forgetting the waypoints.
	 */
	public boolean waypointBeams = true;

	/**
	 * The slider's "No limit" stop, stored as zero. Every range consumer
	 * compares against {@link #waypointRenderDistanceClamped()}, which
	 * turns it into a range no island can exceed.
	 */
	public static final int WAYPOINT_RANGE_NO_LIMIT = 0;

	/**
	 * Beacons further than this many blocks are hidden. Set by the slider on
	 * the HUD tab: 10-250 in steps of ten, or zero for no limit.
	 */
	public int waypointRenderDistance = 30;

	/** The stored range, held to the slider's bounds even if hand-edited. */
	public int waypointRenderDistanceClamped() {
		if (waypointRenderDistance == WAYPOINT_RANGE_NO_LIMIT) {
			// Far past any SkyBlock island, yet small enough that squaring
			// it as a long can never overflow.
			return 100_000;
		}

		return Math.max(10, Math.min(250, waypointRenderDistance));
	}

	/**
	 * Glowing boxes on levers, chests, essence skulls, breakable walls and
	 * bats in the Catacombs, found by scanning loaded blocks - no data files.
	 */
	public boolean secretMarkers = true;

	/** A bazaar price line on item tooltips, from a shared minute-old snapshot. */
	public boolean priceTooltips = true;

	/**
	 * Mouse-wheel scrolling for tooltips taller than the screen, which
	 * Skyblock lore regularly is. Vanilla just crops them.
	 */
	public boolean tooltipScroll = true;

	/**
	 * Pink boxes on unclaimed fairy souls nearby, from the bundled community
	 * locations. Off by default: a player who has most souls already would
	 * see clutter they can only clear by walking to each one.
	 */
	public boolean fairySouls = false;

	/**
	 * Glowing boxes on Garden pests, found by their floating names.
	 */
	public boolean pestHighlight = true;

	/**
	 * Accept Hypixel's required resource pack automatically - the same
	 * per-server "Enabled" state Edit Server offers, set on every connect.
	 */
	public boolean autoResourcePack = true;

	/**
	 * Replace Hypixel's SkyBlock Menu with SkyAid's version - the real one
	 * stays a button away.
	 */
	public boolean menuReplace = true;

	/** A red screen-edge flash when health drops below a quarter. */
	public boolean lowHpWarning = true;

	/** A chat nudge when the Garden composter runs low on fuel or matter. */
	public boolean composterAlert = true;

	/** A chat ping shortly before a Jacob's contest with a watched crop. */
	public boolean jacobAlerts = true;

	/** Crop names the Jacob alert watches; empty means every contest. */
	public java.util.List<String> jacobWatchedCrops = new java.util.ArrayList<>();

	/** A chat heads-up when a bazaar order of yours is undercut. */
	public boolean bazaarWatchdog = true;

	/** Remember what Garden visitors cost; /skyaid visitors shows totals. */
	public boolean visitorLedger = true;

	/**
	 * LEGACY flat yaw,pitch pairs - migrated into the first mouse-lock
	 * group on load, unused after that.
	 */
	public java.util.List<Float> mouseLockPresets = new java.util.ArrayList<>();

	/** A named set of mouse-lock angles - one per farm layout. */
	public static class MouseLockGroup {
		public String name = "Default";

		/** Flat yaw,pitch pairs; slot N is entries 2N-2 and 2N-1. */
		public java.util.List<Float> angles = new java.util.ArrayList<>();
	}

	/** The preset groups; the hold-keys read the ACTIVE group's slots. */
	public java.util.List<MouseLockGroup> mouseLockGroups =
			new java.util.ArrayList<>();

	public int mouseLockActiveGroup;

	/**
	 * Master switch for mouse lock - off makes the preset keys inert.
	 * Ships OFF: a camera that ignores the mouse must be asked for
	 * (/skyaid mouselock on), never a surprise.
	 */
	public boolean mouseLockEnabled = false;

	/**
	 * Skin-texture hashes learned to be essence skulls, discovered by watching
	 * a clicked player head vanish - only collecting a secret does that.
	 * Hypixel decorates with custom-textured player heads too, so texture is
	 * the only thing separating a secret from scenery. Grows on its own.
	 */
	public java.util.List<String> essenceTextures = new java.util.ArrayList<>();

	/**
	 * Highlights puzzle solutions in the Catacombs - the blaze kill order and
	 * creeper beam pairs so far. Display only: it points, the player acts.
	 */
	public boolean puzzleSolvers = true;

	/** F7/M7 terminal overlays - the correct clicks washed green. */
	public boolean terminalOverlays = true;

	/** Reward chest contents summed at market value, one chat line. */
	public boolean chestValue = true;

	/** The real Livid called out on F5/M5 from the glass colour. */
	public boolean lividFinder = true;

	/** The estimated score line ("Score: ~285 (S)") on the dungeon HUD. */
	public boolean dungeonScore = true;

	/** The boss fight and phase clocks on the dungeon HUD. */
	public boolean bossClock = true;

	/**
	 * Shows diagnostic overlays - currently the measured box, cursor position and
	 * screen size on the Move HUD screen. Off by default; it exists because a
	 * misplaced hit box is impossible to reason about without the numbers.
	 */
	public boolean debug = false;

	/** Position and visibility of one HUD element, as a fraction of screen size. */
	public static class HudSettings {
		public boolean visible = true;

		/** 0.0 is the left/top edge, 1.0 the right/bottom - resolution independent. */
		public float x;
		public float y;

		public float scale = 1.0f;

		/**
		 * Hide Hypixel's own sidebar while SkyAid shows the same values, so the two
		 * do not sit on screen saying the same thing twice.
		 *
		 * <p>On by default: replacing that sidebar is the point of this HUD. It only
		 * ever applies on Hypixel, in Skyblock, with this HUD visible - so there is
		 * no state where it hides the sidebar and leaves nothing in its place.
		 */
		public boolean hideHypixelSidebar = true;

		/**
		 * Hides boss bars that are really text banners - hypixel.net adverts,
		 * and the zone-quest Objective banner while SkyAid shows the objective
		 * on its own HUD. Bars it does not recognise always render, so a real
		 * boss encounter can never be hidden by mistake.
		 */
		public boolean hideBannerBossBars = true;

		/**
		 * Draws a dark panel behind the readout, as Hypixel's own sidebar has.
		 * Without it the text is hard to read against a bright sky or snow.
		 */
		public boolean background = true;

		/** How dark the panel is, 0-100. 56 matches the original 0x90 alpha. */
		public int backgroundOpacity = 56;

		/** Where long free-text lines wrap, in pixels (100-300). */
		public int wrapWidth = 150;

		/** Drop shadow under the HUD text; off reads cleaner on the panel. */
		public boolean textShadow = true;

		/** Which readouts the HUD draws. Turn off what you do not look at. */
		public HudElements elements = new HudElements();

		/**
		 * The order the readouts are drawn in, with "divider" entries for separator
		 * rules. Edited through the Arrange screen; unknown ids are dropped and any
		 * element missing from a saved order is appended on load.
		 */
		public java.util.List<String> layout =
				new java.util.ArrayList<>(dev.skyaid.parse.HudLayout.defaultOrder());

		/**
		 * Zone-bound arrangements beyond the standard one: each profile takes
		 * over wherever the location starts with its zone text, so the HUD in
		 * the Catacombs can look different from the hub. Managed entirely from
		 * the Arrange screen - switch, add, remove.
		 */
		public java.util.List<HudProfile> profiles = new java.util.ArrayList<>();

		/**
		 * The profile active at this location, or null for the standard layout.
		 * First match wins, in the order the profiles were added.
		 */
		public HudProfile activeProfile(String location) {
			if (location == null) {
				return null;
			}

			for (HudProfile profile : profiles) {
				if (profile.zone.isBlank()) {
					continue;
				}

				// The Garden reports "Plot - 6" on its field plots, not the
				// island name (screenshot-verified 2026-08-25), so the
				// built-in Garden profile claims those too.
				if (location.startsWith(profile.zone)
						|| (profile.builtin && "The Garden".equals(profile.zone)
								&& location.startsWith("Plot"))) {
					return profile;
				}
			}

			return null;
		}

		/**
		 * Abbreviate large numbers: 10,000 becomes 10k, 7,884,267 becomes 7.9M.
		 * Shorter to read at a glance, at the cost of the exact figure.
		 */
		public boolean shortNumbers = true;

		/**
		 * How far back Coins/h averages, in minutes; 0 means the whole session.
		 * Set by the slider on the HUD tab.
		 */
		public int coinsPerHourWindowMinutes = 60;

		public HudSettings() {
		}

		public HudSettings(float x, float y) {
			this.x = x;
			this.y = y;
		}
	}

	/**
	 * One extra HUD arrangement, bound to a zone. The zone text is both the
	 * profile's name and its trigger: it applies wherever the sidebar location
	 * starts with it, e.g. "The Catacombs" covers every floor.
	 */
	public static class HudProfile {
		public String zone = "";

		/**
		 * Ships with the mod and cannot be removed - the Catacombs profile is
		 * one. Its layout is still fully editable; only the delete is blocked.
		 */
		public boolean builtin = false;

		public java.util.List<String> layout =
				new java.util.ArrayList<>(dev.skyaid.parse.HudLayout.defaultOrder());

		public HudElements elements = new HudElements();
	}

	/**
	 * Per-line switches for the Skyblock HUD. Separate from the values themselves:
	 * a line that is switched off is not drawn even when the sidebar reports it,
	 * which is different from a value simply being absent.
	 */
	public static class HudElements {
		public boolean location = true;
		public boolean time = true;
		public boolean date = true;
		public boolean purse = true;
		public boolean bits = true;

		/**
		 * The session readouts are separate switches, not one block, so each line
		 * can be turned off and placed on its own in the Arrange screen.
		 */
		/**
		 * Session clock and rate ship on; the raw gain deltas ship removed from
		 * the default layouts and default off besides.
		 */
		public boolean session = true;
		public boolean coinsGained = false;
		public boolean coinsPerHour = true;
		public boolean bitsGained = false;

		/** Event countdowns ("Carnival 111:58:05"), lifted out of Other lines. */
		public boolean events = true;

		/**
		 * Player info from the action bar. Off by default: the action bar
		 * already shows all three at the bottom of the screen, so these exist
		 * for arrangements where you want them up with the rest - the
		 * Catacombs profile especially.
		 */
		public boolean health = false;
		public boolean defense = false;
		public boolean mana = false;

		/** The dungeon party members ("[B] G00PED 1.6k❤"), out of Other lines. */
		public boolean party = true;

		/** The dungeon progress lines - Keys, Time Elapsed, Cleared. */
		public boolean dungeon = true;

		/** Dwarven commission progress lines, out of Other in mining zones. */
		public boolean commissions = true;

		/** The Garden readout: widgets, crop rate and row alignment. */
		public boolean garden = true;

		/** The composter block from the tab list. */
		public boolean composter = true;

		/** Visitor count and next-visitor timer. */
		public boolean visitors = true;

		/** Bank balance from the tab list. */
		public boolean bank = true;

		/** The active skill progress line from the tab list. */
		public boolean skill = true;

		/** The Garden Level line alone. */
		public boolean gardenLevel = true;

		/** The Garden fortune stats (Farming Fortune, crop fortunes). */
		public boolean fortune = true;

		/** The pest lines - Pests and Bonus Pest Chance. */
		public boolean pests = true;

		/** The crop milestone line alone. */
		public boolean milestone = true;

		/** Live crops-per-minute alone. */
		public boolean croprate = true;

		/** The row-alignment angle alone. */
		public boolean align = true;

		/** Speed stat from the tab list. */
		public boolean speed = true;

		/** Gems balance from the tab list. */
		public boolean gems = true;

		/** Percent-per-hour rate of the tab list's active skill. */
		public boolean skillrate = true;

		/** Session drop announcements: count and the latest. */
		public boolean drops = true;

		/** Session sea creatures: count and the latest notable. */
		public boolean fishing = true;

		/** The active pet, from the summon chat line. */
		public boolean pet = true;

		/** Museum donation progress, "Museum: 143/312". */
		public boolean museum = true;

		/** The next Jacob's contest and its crops. */
		public boolean jacob = true;

		/** A countdown while an item ability is on cooldown. */
		public boolean cooldown = true;

		/** Mithril/gemstone powder and HOTM lines from the tab list. */
		public boolean powder = true;

		/** Crystal Nucleus runs this session with a rate. */
		public boolean nucleus = true;

		/** Session gemstone blocks mined with an estimated coins/hr. */
		public boolean gemstones = true;

		public boolean slayer = true;
		public boolean objective = true;

		/**
		 * Sidebar lines SkyAid does not recognise, shown as Hypixel wrote them.
		 *
		 * <p>On by default, and worth leaving on: hiding Hypixel's sidebar is also
		 * the default, so without this the dungeon, mining and event sections would
		 * be hidden with nothing put in their place.
		 */
		public boolean other = true;

		/**
		 * Looks a switch up by the id the layout uses, so the arrange screen can
		 * drive visibility without a separate lookup table that could drift out of
		 * step with these fields.
		 */
		public boolean isShown(String id) {
			return switch (id) {
				case "location" -> location;
				case "time" -> time;
				case "date" -> date;
				case "purse" -> purse;
				case "bits" -> bits;
				case "session" -> session;
				case "coinsgained" -> coinsGained;
				case "coinshour" -> coinsPerHour;
				case "bitsgained" -> bitsGained;
				case "health" -> health;
				case "defense" -> defense;
				case "mana" -> mana;
				case "party" -> party;
				case "dungeon" -> dungeon;
				case "commissions" -> commissions;
				case "garden" -> garden;
				case "bank" -> bank;
				case "skill" -> skill;
				case "composter" -> composter;
				case "visitors" -> visitors;
				case "gardenlevel" -> gardenLevel;
				case "fortune" -> fortune;
				case "pests" -> pests;
				case "milestone" -> milestone;
				case "croprate" -> croprate;
				case "align" -> align;
				case "speed" -> speed;
				case "gems" -> gems;
				case "skillrate" -> skillrate;
				case "drops" -> drops;
				case "fishing" -> fishing;
				case "pet" -> pet;
				case "museum" -> museum;
				case "jacob" -> jacob;
				case "cooldown" -> cooldown;
				case "powder" -> powder;
				case "nucleus" -> nucleus;
				case "gemstones" -> gemstones;
				case "slayer" -> slayer;
				case "objective" -> objective;
				case "events" -> events;
				case "other" -> other;
				default -> false;
			};
		}

		public void setShown(String id, boolean shown) {
			switch (id) {
				case "location" -> location = shown;
				case "time" -> time = shown;
				case "date" -> date = shown;
				case "purse" -> purse = shown;
				case "bits" -> bits = shown;
				case "session" -> session = shown;
				case "coinsgained" -> coinsGained = shown;
				case "coinshour" -> coinsPerHour = shown;
				case "bitsgained" -> bitsGained = shown;
				case "health" -> health = shown;
				case "defense" -> defense = shown;
				case "mana" -> mana = shown;
				case "party" -> party = shown;
				case "dungeon" -> dungeon = shown;
				case "commissions" -> commissions = shown;
				case "garden" -> garden = shown;
				case "bank" -> bank = shown;
				case "skill" -> skill = shown;
				case "composter" -> composter = shown;
				case "visitors" -> visitors = shown;
				case "gardenlevel" -> gardenLevel = shown;
				case "fortune" -> fortune = shown;
				case "pests" -> pests = shown;
				case "milestone" -> milestone = shown;
				case "croprate" -> croprate = shown;
				case "align" -> align = shown;
				case "speed" -> speed = shown;
				case "gems" -> gems = shown;
				case "skillrate" -> skillrate = shown;
				case "drops" -> drops = shown;
				case "fishing" -> fishing = shown;
				case "pet" -> pet = shown;
				case "museum" -> museum = shown;
				case "jacob" -> jacob = shown;
				case "cooldown" -> cooldown = shown;
				case "powder" -> powder = shown;
				case "nucleus" -> nucleus = shown;
				case "gemstones" -> gemstones = shown;
				case "slayer" -> slayer = shown;
				case "objective" -> objective = shown;
				case "events" -> events = shown;
				case "other" -> other = shown;
				default -> {
				}
			}
		}
	}

	public static class ChatSettings {
		public boolean hideLobbyJoinSpam = true;
		public boolean hideDuplicateMessages = false;

		/** How far back the repeat filter looks, in seconds. See the slider for limits. */
		public int duplicateWindowSeconds = 5;
		public boolean highlightPartyAndGuild = true;

		/** Hypixel's own store adverts. */
		public boolean hidePromotions = true;

		/** "This ability is on cooldown" spam, often several times a second. */
		public boolean hideAbilityCooldown = true;

		/** "[Sacks] +240 items" collection notices, constant while farming. */
		public boolean hideSackMessages = true;

		/** Prefixes each line with the real-world time it arrived. */
		public boolean timestamps = false;

		/** Shows that time as 2:32pm rather than 14:32. */
		public boolean timestamps12Hour = false;

		/** Marks lines where somebody says your name. */
		public boolean highlightMentions = true;

		/**
		 * Extra words treated like your name: highlighted, and pinged when the
		 * mention sound is on. Managed with /skyaid highlight add|remove|list.
		 */
		public java.util.List<String> highlightWords = new java.util.ArrayList<>();

		/** Marks "[Auction]" announcements - sold, expired, outbid - with a ping. */
		public boolean highlightAuctions = true;

		/**
		 * Coordinates shared in party, guild or direct messages become beacon
		 * waypoints automatically, lapsing after a few minutes or on arrival.
		 */
		public boolean chatWaypoints = true;

		/** Plays a short sound when somebody says your name. */
		public boolean mentionSound = true;

		/** Action-bar nudge + low ping when a notable sea creature spawns. */
		public boolean fishingAlerts = true;
	}
}
