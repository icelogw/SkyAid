package dev.skyaid.feature;

import com.google.gson.JsonObject;
import dev.skyaid.api.HypixelApiClient;
import dev.skyaid.parse.Numbers;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.List;
import java.util.Optional;

/**
 * {@code /skyaid earn [early|mid|late]}: a money-making guide for players who
 * do not know where the coins are. The methods are curated and honest about
 * their requirements; where a method's output trades on the bazaar, the
 * estimate uses the LIVE insta-sell price, so the numbers age with the
 * economy instead of with the guide.
 *
 * <p>Every per-hour figure is a rough what-to-expect for a basic setup - the
 * point is ranking methods against each other, not promising exact income.
 * The Coins/h HUD element measures what a method actually pays.
 */
public final class EarnGuide {
	/** Bazaar cache: same freshness as the price command. */
	private static final long CACHE_MILLIS = 20_000;

	/**
	 * One method: where its output sells ({@code productId} null when it has
	 * no single bazaar product), how many units a basic setup moves per hour
	 * (rough by design), and what it takes to start.
	 */
	record Method(String name, String requirement, String productId,
			double unitsPerHour, String... notes) {
	}

	static final List<Method> EARLY = List.of(
			new Method("Farm on the Garden", "unlock the Garden via the Barn", "SUGAR_CANE", 20000,
					"Free plots, farming tools and visitor rewards -",
					"farm there, not on your island."),
			new Method("Mine coal", "Coal Mine, stone pickaxe", "COAL", 2500,
					"Safe early zones, and the mining XP",
					"opens the Dwarven Mines later."),
			new Method("String from spiders", "Spider's Den, early sword", "STRING", 2500,
					"Combat XP and string in one grind;",
					"enchanted string later doubles the value."),
			new Method("Fishing", "a rod and patience", "RAW_FISH", 1500,
					"Slow coins but treasure drops and XP;",
					"sea creatures pay more as gear grows."),
			new Method("First minions", "a few unlocked slots", null, 0,
					"Set them up early - passive coins",
					"add up while you play."),
			new Method("Island quests", "the main story islands", null, 0,
					"Quests pay coins and unlock the",
					"zones the real money is in."),
			new Method("Bank your coins", "the bank in the Village", null, 0,
					"Banked coins earn interest and cannot",
					"be lost to death or a scammer."));

	static final List<Method> MID = List.of(
			new Method("Bazaar order flips", "a few hundred k to invest", null, 0,
					"Buy with orders, resell with sell offers;",
					"the margin between them is the profit."),
			new Method("Dwarven commissions", "Mining 12", null, 0,
					"Commission payouts plus mithril powder -",
					"strong coins and mining progression."),
			new Method("Garden farming at scale", "levelled crops, farming fortune", "SUGAR_CANE", 100000,
					"Plot upgrades and crop milestones;",
					"Jacob's contests pay on top."),
			new Method("Greenhouse (passive)", "a Greenhouse plot on the Garden", null, 0,
					"Grows crops over real hours, even offline -",
					"plant, wait, harvest, repeat."),
			new Method("Revenant slayers", "combat gear, Zombie slayer", "REVENANT_FLESH", 600,
					"Flesh sells steadily; rare drops spike it,",
					"and slayer levels unlock better methods."),
			new Method("Tarantula slayers", "Spider slayer unlocked", "TARANTULA_WEB", 500,
					"Webs sell steadily and the slayer",
					"levels open stronger gear."),
			new Method("Zealot grinding", "The End unlocked, decent sword", "SUMMONING_EYE", 4,
					"Summoning Eyes carry the value -",
					"the rate climbs a lot with better gear."),
			new Method("The Rift", "Rift access from the Wizard", null, 0,
					"Quick to progress and unlocks several",
					"steady earners along the way."),
			new Method("Minion arrays", "more slots, compactors", null, 0,
					"Upgrade tiers, add compactors,",
					"collect and sell on a schedule."));

	static final List<Method> LATE = List.of(
			new Method("Dillo mining", "deep Mining progression, Precursor Remnants", null, 0,
					"Top-end mining money - the highest",
					"per-hour claims in the game right now."),
			new Method("Pest farming", "a built-out Garden, pest gear", null, 0,
					"The farming meta: community figures",
					"run 30-40M/hr with a proper setup."),
			new Method("Dungeon runs", "Catacombs progression, a team", null, 0,
					"Chest loot plus gear that sells on the AH;",
					"profit scales hard with floor and speed."),
			new Method("Gemstones & mineshafts", "Heart of the Mountain, Crystal Hollows", null, 0,
					"Gemstone powder multiplies the rate;",
					"glacite mineshafts spike it further."),
			new Method("Kuudra", "Crimson Isle progression, a team", null, 0,
					"Kuudra runs feed the best gear",
					"market on the AH."),
			new Method("Voidgloom slayers", "Enderman slayer, strong gear", null, 0,
					"Handles and hearts are worth millions",
					"once you can clear tiers fast."),
			new Method("Diana events", "her mayor term, Griffin pet", null, 0,
					"Mythological burrows drop pets and",
					"relics that sell for a lot."),
			new Method("Lava fishing", "Crimson Isle, lava rod", null, 0,
					"Trophy fish and lava sea creatures;",
					"steady money with a relaxed pace."),
			new Method("Auction flipping", "capital and price knowledge", null, 0,
					"Buy underpriced BINs, relist at value;",
					"check items before you commit."));

	/**
	 * The long-form page behind each method's Details button: how to start,
	 * the route, and the tips that make it pay. Lines are pre-wrapped to the
	 * menu's width. Methods without an entry fall back to their notes.
	 */
	static final java.util.Map<String, String[]> DETAILS = java.util.Map.ofEntries(
			java.util.Map.entry("Farm on the Garden", new String[]{
					"Unlock: visit the Barn island, then accept the",
					"Garden invite there.",
					"Start with wheat on the starter plots; unlock",
					"more plots with compost from the composter.",
					"Take visitor offers - they pay coins and copper,",
					"and copper buys Garden shop upgrades.",
					"Sugar cane pays better once available; sell",
					"stacks on the bazaar from Skyblock level 7."}),
			java.util.Map.entry("Mine coal", new String[]{
					"Route: Village -> Coal Mine, then down into",
					"the Deep Caverns as your gear improves.",
					"A stone pickaxe is enough to start; let the",
					"drops fund upgrades.",
					"Sell coal on the bazaar, but keep some as",
					"minion fuel - it speeds your minions up.",
					"Mining XP here unlocks the Dwarven Mines at",
					"Mining 12 - a big money step up."}),
			java.util.Map.entry("String from spiders", new String[]{
					"Kill spiders around the Spider's Den entrance;",
					"they die fast to any starter sword.",
					"String sells on the bazaar; the Enchanted",
					"String recipe roughly doubles the value.",
					"The combat XP feeds your first slayer",
					"unlocks - this grind pays twice."}),
			java.util.Map.entry("Fishing", new String[]{
					"Any rod works to start - fish at the Village",
					"pond or on your island.",
					"Treasure chests and fishing XP are the real",
					"early value; sell the fish on the bazaar.",
					"Upgrade rods as you go: sea creatures become",
					"serious money with better gear."}),
			java.util.Map.entry("First minions", new String[]{
					"Place every minion your collections have",
					"unlocked - slots grow with unique minions.",
					"Cobblestone and farming minions are fine",
					"early; collect and sell each session.",
					"Fuel them (coal, enchanted bread) - uptime",
					"and speed are the whole game for minions."}),
			java.util.Map.entry("Island quests", new String[]{
					"Follow each island's quest line - they pay",
					"coins directly and unlock zones and NPCs.",
					"Prioritise the Barn and the Mines: they open",
					"the Garden and Dwarven money-makers."}),
			java.util.Map.entry("Bank your coins", new String[]{
					"Talk to the banker in the Village; deposits",
					"earn interest every in-game season.",
					"Carried coins can be lost on death in some",
					"zones and to scams - banked coins cannot.",
					"Keep a working float in your purse and bank",
					"the rest whenever you pass through."}),
			java.util.Map.entry("Bazaar order flips", new String[]{
					"Place BUY ORDERS just above the current top",
					"order, SELL OFFERS just under the lowest.",
					"The gap minus the 1.25% tax is your profit;",
					"high-volume items fill fastest.",
					"Start with a few hundred k spread over 2-3",
					"items, and re-check prices before relisting.",
					"The Margins button lists live candidates."}),
			java.util.Map.entry("Dwarven commissions", new String[]{
					"At Mining 12, take commissions from the King",
					"and the board in the Dwarven Mines.",
					"Payouts stack coins plus mithril powder,",
					"which buys Heart of the Mountain upgrades.",
					"Rotate mithril, titanium and event",
					"commissions while they are boosted."}),
			java.util.Map.entry("Garden farming at scale", new String[]{
					"Level crops for milestones and take plot",
					"upgrades as they unlock.",
					"Farming fortune is the multiplier - armour,",
					"tools and pet all stack it.",
					"Enter Jacob's contests for medals and",
					"rewards; sell output on the bazaar."}),
			java.util.Map.entry("Greenhouse (passive)", new String[]{
					"Build a Greenhouse plot on the Garden, then",
					"plant and water crops that grow over real",
					"hours - even while you are offline.",
					"Harvest on login, replant, repeat.",
					"It also feeds crop milestones, so it stacks",
					"with your active farming."}),
			java.util.Map.entry("Revenant slayers", new String[]{
					"Start Zombie slayer quests from Maddox; run",
					"the boss tier you can clear FAST -",
					"speed beats tier for coins per hour.",
					"Sell Revenant Flesh on the bazaar; the rare",
					"drops are the jackpots.",
					"Slayer levels unlock the gear that carries",
					"the rest of mid game."}),
			java.util.Map.entry("Tarantula slayers", new String[]{
					"Spider slayer from Maddox once your combat",
					"allows; webs sell steadily on the bazaar.",
					"Same rule as all slayers: a tier cleared",
					"quickly earns more than a slow higher one."}),
			java.util.Map.entry("Zealot grinding", new String[]{
					"Zealots live in the Dragon's Nest in The",
					"End; gear up until they die in one hit.",
					"Summoning Eyes are the payday - sell them,",
					"or save for ender-pet setups later.",
					"An Aspect of the End teleport speeds the",
					"loop between packs."}),
			java.util.Map.entry("The Rift", new String[]{
					"Enter through the Rift portal; progression",
					"is quick and mostly quests and puzzles.",
					"The rewards along the way unlock steady",
					"earners and QoL items worth real coins."}),
			java.util.Map.entry("Minion arrays", new String[]{
					"Fill every slot with your best-tier minions,",
					"add compactors and storage.",
					"Pick outputs that sell well on the bazaar",
					"and collect on a fixed schedule.",
					"Fuel everything - uptime is the profit."}),
			java.util.Map.entry("Dillo mining", new String[]{
					"In the Precursor Remnants of the Crystal",
					"Hollows; needs deep mining progression.",
					"The top per-hour claims in the game right",
					"now - but the meta moves fast, so check an",
					"up-to-date route guide before investing."}),
			java.util.Map.entry("Pest farming", new String[]{
					"Pests spawn while you farm on the Garden;",
					"kill them with pest-focused gear for drops.",
					"Community figures run 30-40M/hr with a",
					"proper setup - it needs a built-out Garden",
					"and dedicated gear first."}),
			java.util.Map.entry("Dungeon runs", new String[]{
					"Profit is chest loot plus gear that sells on",
					"the AH; floor and clear speed scale it hard.",
					"A steady team beats random groups for rate;",
					"master mode multiplies risk and reward."}),
			java.util.Map.entry("Gemstones & mineshafts", new String[]{
					"Mine the Crystal Hollows gemstone fields;",
					"powder upgrades multiply the yield.",
					"Glacite mineshafts and their corpses spike",
					"the rate - learn the mineshaft rotation."}),
			java.util.Map.entry("Kuudra", new String[]{
					"Crimson Isle endgame boss runs with a team;",
					"reputation and keys gate the tiers.",
					"The drops feed the strongest gear market on",
					"the AH - sell or gear up and go higher."}),
			java.util.Map.entry("Voidgloom slayers", new String[]{
					"Enderman slayer from Maddox; needs strong",
					"gear and good movement.",
					"Handles and hearts sell for millions - but",
					"only once you clear tiers quickly."}),
			java.util.Map.entry("Diana events", new String[]{
					"Only during mayor Diana's term: dig burrow",
					"chains with the Ancestral Spade.",
					"Griffin burrow drops - relics, pets, the",
					"stick - sell high; a better Griffin pet",
					"raises the odds."}),
			java.util.Map.entry("Lava fishing", new String[]{
					"Fish the Crimson Isle lava with a lava rod;",
					"trophy fish and lava creatures both pay.",
					"Slower than the other late methods but",
					"steady, and semi-relaxed to run."}),
			java.util.Map.entry("Auction flipping", new String[]{
					"Scan BIN listings for underpriced items,",
					"buy, and relist at market value.",
					"/skyaid price checks an item's numbers fast.",
					"Know one niche well - pets, a gear tier -",
					"specialists beat generalists here."}));

	/** The Details page for a method; notes stand in when none is written. */
	static String[] detailsFor(Method method) {
		return DETAILS.getOrDefault(method.name(), method.notes());
	}

	private EarnGuide() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("tutorial")
								.executes(context -> {
									// Deferred a tick so the closing chat screen
									// cannot swallow the menu (museum pattern).
									Minecraft.getInstance().execute(() ->
											Minecraft.getInstance().setScreenAndShow(
													new EarnGuideScreen()));
									return 1;
								})
								.then(ClientCommands.literal("early").executes(context -> {
									tier("Early game - first million", EARLY,
							"The Bazaar unlocks at Skyblock level 7 - until then,"
									+ " sell to NPCs (they pay less).");
									return 1;
								}))
								.then(ClientCommands.literal("mid").executes(context -> {
									tier("Mid game - roughly 1-50M", MID, null);
									return 1;
								}))
								.then(ClientCommands.literal("late").executes(context -> {
									tier("Late game - 50M and beyond", LATE, null);
									return 1;
								})))));
	}

	/** One tier's methods, priced live where the bazaar trades the output. */
	private static void tier(String title, List<Method> methods, String caveat) {
		HypixelApiClient.get("/skyblock/bazaar", CACHE_MILLIS, false)
				.thenAccept(body -> Minecraft.getInstance().execute(
						() -> reportTier(title, methods, caveat, body)));
	}

	private static void reportTier(String title, List<Method> methods,
			String caveat, Optional<JsonObject> body) {
		JsonObject products = body.map(json -> json.getAsJsonObject("products"))
				.orElse(null);

		var message = Component.literal(title).withStyle(ChatFormatting.AQUA);

		if (caveat != null) {
			message = message.copy().append(Component.literal("\n  " + caveat)
					.withStyle(ChatFormatting.RED));
		}

		for (Method method : methods) {
			message = message.copy()
					.append(Component.literal("\n  " + method.name()).withStyle(
							ChatFormatting.YELLOW, ChatFormatting.BOLD))
					.append(Component.literal("  (" + method.requirement() + ")")
							.withStyle(ChatFormatting.DARK_GRAY));

			Optional<Double> price = sellPrice(products, method.productId());

			if (price.isPresent()) {
				long hourly = Math.round(method.unitsPerHour() * price.get());
				message = message.copy()
						.append(Component.literal("\n    ~" + Numbers.shorten(hourly)
										+ "/hr at current prices")
								.withStyle(ChatFormatting.GOLD))
						.append(Component.literal("  "))
						.append(button("[Open Bazaar]",
								"/bz " + dev.skyaid.parse.ItemNames.cleanForSearch(
										dev.skyaid.parse.Bazaar.displayName(method.productId())),
								"Opens the bazaar for "
										+ dev.skyaid.parse.Bazaar.displayName(method.productId())));
			}

			for (String note : method.notes()) {
				message = message.copy().append(
						Component.literal("\n    " + note).withStyle(ChatFormatting.GRAY));
			}

			// The flip method's live numbers already exist one command away.
			if (method.name().startsWith("Bazaar order flips")) {
				message = message.copy()
						.append(Component.literal("\n    "))
						.append(button("[Show live flip margins]", "/skyaid flips",
								"Runs /skyaid flips"));
			}
		}

		message = message.copy().append(Component.literal(
						"\n  Per-hour figures are rough, for a basic setup - they rank"
								+ "\n  methods, they are not promises.")
				.withStyle(ChatFormatting.DARK_GRAY));

		say(message);
	}

	/** The live insta-sell price of a product, if the bazaar answered. */
	static Optional<Double> sellPrice(JsonObject products, String productId) {
		if (products == null || productId == null
				|| !products.has(productId)) {
			return Optional.empty();
		}

		JsonObject quick = products.getAsJsonObject(productId)
				.getAsJsonObject("quick_status");

		if (quick == null || !quick.has("sellPrice")) {
			return Optional.empty();
		}

		double price = quick.get("sellPrice").getAsDouble();
		return price > 0 ? Optional.of(price) : Optional.empty();
	}

	/** The same one-click, one-command button shape the price command uses. */
	private static Component button(String label, String command, String hint) {
		return Component.literal(label).withStyle(style -> style
				.withColor(ChatFormatting.GREEN)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(
						Component.literal(hint).withStyle(ChatFormatting.GRAY))));
	}

	private static void say(Component message) {
		var client = Minecraft.getInstance();

		if (client.gui != null) {
			// A blank line either side, the same breathing room the help
			// and session blocks get.
			var chat = client.gui.hud.getChat();
			chat.addClientSystemMessage(Component.empty());
			chat.addClientSystemMessage(message);
			chat.addClientSystemMessage(Component.empty());
		}
	}
}
