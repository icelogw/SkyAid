package dev.skyaid;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.SessionTracker;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.dungeon.core.DungeonTracker;
import dev.skyaid.dungeon.core.MarkerRenderer;
import dev.skyaid.dungeon.core.SecretsBoard;
import dev.skyaid.dungeon.solvers.Solvers;
import dev.skyaid.feature.ApiKeyCommand;
import dev.skyaid.feature.ApiKeyNotice;
import dev.skyaid.feature.AuctionsCommand;
import dev.skyaid.feature.ChatCleanup;
import dev.skyaid.feature.HighlightCommand;
import dev.skyaid.feature.SessionCommand;
import dev.skyaid.feature.SidebarDump;
import dev.skyaid.feature.SkyAidHelp;
import dev.skyaid.feature.StatsLookup;
import dev.skyaid.feature.Waypoints;
import dev.skyaid.hud.BossBarFilter;
import dev.skyaid.hud.DungeonMap;
import dev.skyaid.hud.SkyblockHud;
import dev.skyaid.hud.VanillaSidebar;
import dev.skyaid.hud.WaypointRenderer;
import dev.skyaid.keybind.Keybinds;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Wires subsystems together and holds no feature logic itself.
 *
 * <p>The rule this whole mod is built around: it displays information, it never
 * acts. Nothing here may click, move, swing, send a command or otherwise take a
 * gameplay action on the player's behalf. Hypixel bans automation outright, and
 * every feature is additionally gated on actually being connected to Hypixel.
 */
public class SkyAidClient implements ClientModInitializer {
	public static final String MOD_ID = "skyaid";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		ConfigManager.load();
		SkyblockTracker.register();
		SessionTracker.register();
		dev.skyaid.core.SlayerTracker.register();
		SkyblockHud.register();
		DungeonMap.register();
		DungeonTracker.register();
		SecretsBoard.register();
		MarkerRenderer.register();
		Solvers.register();
		dev.skyaid.dungeon.LividFinder.register();
		dev.skyaid.feature.ChestValue.register();
		WaypointRenderer.register();
		Waypoints.register();
		VanillaSidebar.register();
		BossBarFilter.register();
		ChatCleanup.register();
		dev.skyaid.feature.FishingAlerts.register();
		dev.skyaid.feature.ChatButtons.register();
		dev.skyaid.core.PetTracker.register();
		dev.skyaid.core.LifetimeStats.register();
		dev.skyaid.feature.QuickJoin.register();
		dev.skyaid.feature.FairySouls.register();
		dev.skyaid.dungeon.terminals.TerminalSolvers.register();
		StatsLookup.register();
		AuctionsCommand.register();
		dev.skyaid.feature.PriceCommand.register();
		dev.skyaid.feature.EarnGuide.register();
		dev.skyaid.feature.PestHighlight.register();
		dev.skyaid.core.CropRateTracker.register();
		dev.skyaid.feature.VisitorCost.register();
		dev.skyaid.feature.ResourcePackAccept.register();
		dev.skyaid.feature.MouseLock.register();
		dev.skyaid.core.DropTracker.register();
		dev.skyaid.feature.SkyMenu.register();
		dev.skyaid.feature.PriceTooltips.register();
		dev.skyaid.feature.MuseumTracker.register();
		dev.skyaid.feature.QuickSearchKeys.register();
		dev.skyaid.feature.SignSearchAssist.register();
		dev.skyaid.feature.TooltipScroll.register();
		SessionCommand.register();
		dev.skyaid.feature.LowHpWarning.register();
		dev.skyaid.feature.ComposterAlert.register();
		dev.skyaid.feature.CooldownTracker.register();
		dev.skyaid.feature.JacobContests.register();
		dev.skyaid.feature.BazaarOrders.register();
		dev.skyaid.feature.VisitorLedger.register();
		dev.skyaid.feature.DungeonRunSummary.register();
		dev.skyaid.feature.CraftProfit.register();
		dev.skyaid.feature.ReportCommand.register();
		dev.skyaid.feature.CrystalWaypoints.register();
		dev.skyaid.feature.NucleusRuns.register();
		dev.skyaid.feature.GemstoneSession.register();
		HighlightCommand.register();
		SidebarDump.register();
		SkyAidHelp.register();
		ApiKeyCommand.register();
		ApiKeyNotice.register();
		Keybinds.register();

		LOGGER.info("SkyAid ready");
	}
}
