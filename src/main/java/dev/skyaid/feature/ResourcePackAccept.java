package dev.skyaid.feature;

import dev.skyaid.SkyAidClient;
import dev.skyaid.config.ConfigManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

import java.util.Locale;

/**
 * Remembers "Proceed" on Hypixel's resource-pack prompt by holding the
 * server entry in vanilla's own "Server Resource Packs: Enabled" state -
 * exactly what Edit Server sets - so the pack applies without the dialog.
 *
 * <p>Field lesson (2026-08-25): setting the status once at configuration
 * start was not enough - the prompt still appeared, so the timing of that
 * hook loses some race. Now it is held three ways: the SAVED server list
 * entries are fixed once at startup (persisted to servers.dat, like Edit
 * Server), the LIVE connection's entry is re-checked every two seconds, and
 * every change is logged so the game log shows what happened.
 *
 * <p>Hypixel only: other servers keep asking, as vanilla intends. No screen
 * is clicked and no input is synthesised.
 */
public final class ResourcePackAccept {
	private static final int CHECK_TICKS = 40;

	private static int tickCounter;
	private static boolean listChecked;

	/** The hostname the player actually typed/picked, noted at connect. */
	private static volatile String lastTargetHost = "";

	private ResourcePackAccept() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (++tickCounter < CHECK_TICKS) {
				return;
			}

			tickCounter = 0;

			if (!ConfigManager.get().enabled
					|| !ConfigManager.get().autoResourcePack) {
				return;
			}

			// One pass over the saved list per session: any Hypixel entry
			// becomes Enabled on disk, the same edit the server screen makes.
			if (!listChecked) {
				listChecked = true;
				fixSavedList(client);
			}

			// And the live connection, every couple of seconds - covers
			// entries the list pass could not see (direct/quick-play joins)
			// and any code path that reset the flag.
			ServerData current = client.getCurrentServer();

			if (current != null && isHypixel(current)
					&& current.getResourcePackStatus() != ServerData.ServerPackStatus.ENABLED) {
				current.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
				SkyAidClient.LOGGER.info(
						"Enabled server resource packs for the current Hypixel connection");
			}
		});
	}

	private static void fixSavedList(net.minecraft.client.Minecraft client) {
		try {
			ServerList list = new ServerList(client);
			list.load();
			boolean changed = false;

			for (int i = 0; i < list.size(); i++) {
				ServerData server = list.get(i);

				if (isHypixel(server) && server.getResourcePackStatus()
						!= ServerData.ServerPackStatus.ENABLED) {
					server.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
					changed = true;
					SkyAidClient.LOGGER.info(
							"Enabled server resource packs for saved server '{}'",
							server.ip);
				}
			}

			if (changed) {
				list.save();
			}
		} catch (Exception e) {
			// A broken servers.dat is vanilla's problem to surface, not ours.
			SkyAidClient.LOGGER.warn("Could not update the saved server list");
		}
	}

	/**
	 * The mixin's hook, called at every (re)configuration: a PENDING that
	 * would prompt becomes ALLOWED on Hypixel. Logged, so the game log
	 * shows which path actually ran.
	 */
	public static net.minecraft.client.resources.server.ServerPackManager.PackPromptStatus
			promptOverride(net.minecraft.client.resources.server.ServerPackManager
					.PackPromptStatus status,
					net.minecraft.network.Connection connection) {
		// Judged from the CONNECTION, not getCurrentServer(): this runs so
		// early that the current-server field can still be null.
		boolean hypixel = dev.skyaid.parse.ServerAddresses.isHypixel(lastTargetHost);

		if (!hypixel && connection != null && connection.getRemoteAddress() != null) {
			// InetSocketAddress renders as "hostname/ip:port".
			String remote = connection.getRemoteAddress().toString();
			int slash = remote.indexOf('/');
			hypixel = slash > 0 && dev.skyaid.parse.ServerAddresses.isHypixel(
					remote.substring(0, slash));
		}

		if (!hypixel) {
			ServerData server =
					net.minecraft.client.Minecraft.getInstance().getCurrentServer();
			hypixel = server != null && isHypixel(server);
		}

		SkyAidClient.LOGGER.info("Server pack configure: status {} hypixel {}",
				status, hypixel);

		if (status != net.minecraft.client.resources.server.ServerPackManager
				.PackPromptStatus.PENDING
				|| !hypixel
				|| !ConfigManager.get().enabled
				|| !ConfigManager.get().autoResourcePack) {
			return status;
		}

		SkyAidClient.LOGGER.info(
				"Auto-allowing Hypixel's server resource pack (configuration)");
		return net.minecraft.client.resources.server.ServerPackManager
				.PackPromptStatus.ALLOWED;
	}

	/** Called by ConnectScreenMixin on the client thread, pre-handshake. */
	public static void noteTarget(String host) {
		lastTargetHost = host == null ? "" : host;
	}

	private static boolean isHypixel(ServerData server) {
		return dev.skyaid.parse.ServerAddresses.isHypixel(server.ip);
	}
}
