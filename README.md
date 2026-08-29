# SkyAid

A client-side quality-of-life mod for Hypixel SkyBlock, for **Minecraft 26.2 (Fabric)**.

SkyAid shows you information - prices, progress, markers, timers - and tracks
your stats while you play.

Full command reference: [COMMANDS.md](COMMANDS.md).

## What it does

- **HUD** - a fully arrangeable readout (purse, coins/hour, bank, skills, pet,
  session stats, drop and fishing trackers, and more), with built-in layouts
  for the Catacombs and the Garden. Every line moves or hides individually.
- **Prices** - bazaar and NPC prices on tooltips, `/skyaid price` and
  `/skyaid craft` lookups, flip-margin scans, reward-chest values, and a
  watchdog that says when your bazaar order is undercut.
- **Museum** - donation tracking, a missing-list browser, and a
  cheapest-XP-per-coin ranking at live prices.
- **Dungeons** - the map with per-room secret counts, secret markers from the
  community room database, puzzle and terminal overlays, the real Livid, an
  estimated score, and end-of-run summaries.
- **Garden** - visitor offers priced before you accept, a visitor ledger, pest
  highlighting with guide lines, Jacob's contest tracking, a composter alert,
  and a hold-to-lock mouse lock for farming rows (off by default).
- **Crystal Hollows** - structure waypoints from chat callouts, powder and
  HOTM readouts, nucleus run and gemstone mining trackers.
- **Chat & players** - optional spam cleanup, party/guild highlights, one-click
  `[stats]`/`[Lookup]` buttons, and `/skyaid stats <player>` with a networth
  estimate.
- **All-time stats** - a lifetime ledger of active time, coins, runs, deaths,
  drops, and more, kept across sessions.

## What it will never do

This mod **displays information; it does not act.** It contains no
auto-clicking, no macros, no aim or reach assistance, no auto-sprint, no
auto-fishing, no synthesized input, and it sends no modified packets to
Hypixel. Hypixel bans automation outright, and this project treats that as a
hard design constraint rather than a setting. One click or keypress from you
is at most one action - the mouse lock, the most sensitive feature, works
only while a key is physically held, so it cannot do anything unattended.

Every feature is additionally gated on actually being connected to
`hypixel.net`, so the mod is completely inert in singleplayer and on other
servers.

### A note on Hypixel's rules

Hypixel's [allowed modifications policy](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications)
formally permits four categories: performance, aesthetic, cosmetic HUD changes
*"without adding extra information which would normally be unavailable to the
player"*, and brightness/gamma. Anything outside those is "disallowed by
default". Features here that surface API data or mark secrets go beyond the
letter of that policy, in line with what the mainstream SkyBlock mods
(SkyHanni, NEU, Firmament) do in practice. **Use at your own risk**, and read
the policy yourself before deciding.

## Setup

Requires **JDK 25** (Minecraft 26.2 targets Java 25).

```bash
./gradlew build      # builds build/libs/skyaid-<version>.jar
./gradlew test       # runs the parser unit tests
./gradlew runClient  # launches a dev client with the mod loaded
```

### API key

The museum and stats features use a Hypixel API key from
[developer.hypixel.net](https://developer.hypixel.net/dashboard) - optional,
entered in-game with `/skyaid add key`.

The key is treated as a credential: it is sent only to `api.hypixel.net`, only
in the `API-Key` header (never a URL), and is never written to the log.

### Dependencies

[Fabric API](https://modrinth.com/mod/fabric-api) is required.
[Mod Menu](https://modrinth.com/mod/modmenu) (optional) adds a settings button
in its mod list. [Hypixel Mod API](https://modrinth.com/mod/hypixel-mod-api)
(optional) provides more reliable location data than reading the scoreboard.

## Layout

| Package | Role |
|---|---|
| `parse/` | Pure string parsing, no Minecraft types - fully unit tested |
| `core/` | Hypixel detection, scoreboard/tab reading, state and stat tracking |
| `api/` | Hypixel and community API clients, TTL caches, rate limiting |
| `feature/` | The features: prices, museum, garden, chat, menus, alerts |
| `dungeon/` | Map, room database, secrets, solvers, terminals |
| `hud/` | HUD rendering and arrangement |
| `config/` | JSON config and the settings screen |
| `keybind/` | Key bindings |

`parse/` deliberately has no Minecraft dependency: Hypixel rewords its
scoreboard and chat regularly, so that package plus its fixture tests are the
regression suite that actually matters.

## Data & credits

- Dungeon room and secret data from the
  [Dungeon Rooms Mod](https://github.com/Quantizr/DungeonRoomsMod)
  (Quantizr, GPL-3.0) - see `assets/skyaid/dungeonrooms/NOTICE.txt`.
- Fairy soul locations from the NEU community data.
- Jacob's contest schedule from [elitebot.dev](https://elitebot.dev);
  auction prices from [Coflnet](https://sky.coflnet.com).

## Licence

[GPL-3.0](LICENSE). Not affiliated with Hypixel or Mojang.
