package dev.skyaid.feature;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.parse.ChatCoords;
import dev.skyaid.parse.ChatRules;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Beacon markers: {@code /skyaid waypoint add <name>} plants one where you
 * stand - anywhere - and in the Catacombs, coordinates called out in party,
 * guild or direct messages plant one automatically. Drawn by
 * {@link dev.skyaid.hud.WaypointRenderer}.
 *
 * <p>The split is deliberate: a manual marker is useful on any island, while
 * the automatic ones exist for dungeon callouts and would be noise elsewhere.
 *
 * <p>Display only, and entirely player-driven: markers exist only where the
 * player or a teammate said, live in memory, and never touch the server.
 */
public final class Waypoints {
	/**
	 * @param expiresAt wall-clock millis when an automatic waypoint lapses;
	 *                  0 for manual waypoints, which stay until removed
	 * @param armed     set once the player has been away from the spot, so a
	 *                  marker placed underfoot does not instantly count as
	 *                  reached and delete itself
	 */
	public record Waypoint(String name, BlockPos pos, int color, long expiresAt,
			boolean armed) {
		Waypoint armedCopy() {
			return new Waypoint(name, pos, color, expiresAt, true);
		}
	}

	/** Arriving this close to an armed waypoint removes it - job done. */
	private static final double REACHED_SQ = 4 * 4;

	/** Being this far away arms a waypoint for removal-on-arrival. */
	private static final double ARM_SQ = 8 * 8;

	/**
	 * How long a waypoint made from chat coordinates lasts. Long enough to walk
	 * there; short enough that stale callouts do not litter the world.
	 */
	private static final long SHARED_LIFETIME_MILLIS = 3 * 60 * 1000L;

	/** Enough for a route; a cap only so the screen cannot fill with beams. */
	private static final int MAX_WAYPOINTS = 16;

	/** Beam colours, cycled in order - distinct against most skies. */
	private static final int[] COLORS = {
			0x00E5FF, 0xFFD24D, 0x20E320, 0xFF66E0, 0xFF8A3D, 0x8F9BFF};

	private static final List<Waypoint> ACTIVE = new CopyOnWriteArrayList<>();
	private static int nextColor;

	private Waypoints() {
	}

	public static List<Waypoint> all() {
		return ACTIVE;
	}

	/**
	 * Whether chat callouts may create waypoints: Catacombs only - except in
	 * debug mode, which lifts the gate so it can be tested outside a dungeon.
	 */
	private static boolean chatWaypointsAvailable() {
		return ConfigManager.get().debug || SkyblockTracker.state().inCatacombs();
	}

	public static void register() {
		// Waypoints belong to the world they were placed in; disconnecting makes
		// them meaningless, so they clear rather than reappear somewhere else.
		// Every waypoint removes itself on arrival - once armed by having been
		// away from it, so a marker placed underfoot survives its own creation -
		// and the automatic ones also lapse on their timer.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (ACTIVE.isEmpty()) {
				return;
			}

			if (client.level == null) {
				ACTIVE.clear();
				return;
			}

			long now = System.currentTimeMillis();

			for (int i = 0; i < ACTIVE.size(); i++) {
				Waypoint waypoint = ACTIVE.get(i);

				if (waypoint.expiresAt() != 0 && now >= waypoint.expiresAt()) {
					ACTIVE.remove(i--);
					continue;
				}

				if (client.player == null) {
					continue;
				}

				double distSq = client.player.blockPosition().distSqr(waypoint.pos());

				if (!waypoint.armed() && distSq >= ARM_SQ) {
					ACTIVE.set(i, waypoint.armedCopy());
				} else if (waypoint.armed() && distSq <= REACHED_SQ) {
					ACTIVE.remove(i--);
				}
			}
		});

		// Coordinates called out in party, guild or direct messages become
		// waypoints on their own - the whole point of a callout is being found.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !ConfigManager.get().enabled
					|| !ConfigManager.get().chat.chatWaypoints
					|| !chatWaypointsAvailable()) {
				return;
			}

			String text = message.getString();

			switch (ChatRules.classify(text)) {
				case PARTY, GUILD, DIRECT_MESSAGE -> ChatCoords.parse(text)
						.ifPresent(Waypoints::addShared);
				default -> {
				}
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("waypoint")
								.executes(context -> {
									list(context.getSource());
									return 1;
								})
								.then(ClientCommands.literal("list")
										.executes(context -> {
											list(context.getSource());
											return 1;
										}))
								.then(ClientCommands.literal("clear")
										.executes(context -> {
											ACTIVE.clear();
											say(context.getSource(),
													Component.literal("All waypoints removed.")
															.withStyle(ChatFormatting.GREEN));
											return 1;
										}))
								.then(ClientCommands.literal("add")
										.then(ClientCommands.argument(
														"name", StringArgumentType.greedyString())
												.executes(context -> {
													add(context.getSource(), StringArgumentType
															.getString(context, "name"));
													return 1;
												})))
								.then(ClientCommands.literal("remove")
										.then(ClientCommands.argument(
														"name", StringArgumentType.greedyString())
												.executes(context -> {
													remove(context.getSource(), StringArgumentType
															.getString(context, "name"));
													return 1;
												}))))));
	}

	private static void add(FabricClientCommandSource source, String rawName) {
		String name = rawName.trim();

		if (name.isEmpty() || name.length() > 32) {
			say(source, Component.literal("Waypoint names are 1-32 characters.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		if (find(name) != null) {
			say(source, Component.literal("There is already a waypoint called \"" + name + "\".")
					.withStyle(ChatFormatting.RED));
			return;
		}

		if (ACTIVE.size() >= MAX_WAYPOINTS) {
			say(source, Component.literal("That is " + MAX_WAYPOINTS
							+ " waypoints already - remove one first.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		BlockPos pos = source.getPlayer().blockPosition();
		ACTIVE.add(new Waypoint(name, pos, COLORS[nextColor++ % COLORS.length], 0, false));

		say(source, Component.literal("Waypoint \"" + name + "\" set at "
						+ pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ".")
				.withStyle(ChatFormatting.GREEN));
	}

	private static void remove(FabricClientCommandSource source, String rawName) {
		Waypoint waypoint = find(rawName.trim());

		if (waypoint == null) {
			say(source, Component.literal("No waypoint called \"" + rawName.trim() + "\".")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		ACTIVE.remove(waypoint);
		say(source, Component.literal("Waypoint \"" + waypoint.name() + "\" removed.")
				.withStyle(ChatFormatting.GREEN));
	}

	private static void list(FabricClientCommandSource source) {
		if (ACTIVE.isEmpty()) {
			say(source, Component.literal(
							"No waypoints. Add one with /skyaid waypoint add <name>.")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		source.sendFeedback(Component.empty());
		source.sendFeedback(Component.literal("Waypoints:").withStyle(ChatFormatting.AQUA));

		for (Waypoint waypoint : ACTIVE) {
			BlockPos pos = waypoint.pos();
			source.sendFeedback(Component.literal("  " + waypoint.name())
					.withStyle(ChatFormatting.WHITE)
					.append(Component.literal("  " + pos.getX() + ", " + pos.getY()
									+ ", " + pos.getZ())
							.withStyle(ChatFormatting.GRAY)));
		}

		source.sendFeedback(Component.empty());
	}

	/**
	 * A waypoint from chat coordinates: replaces an earlier callout under the
	 * same label - people correct themselves - and lapses on its own.
	 */
	private static void addShared(ChatCoords.Shared shared) {
		// Your own callout: the spot is under your feet, and a marker there
		// would count as reached and vanish before it was ever seen.
		var player = net.minecraft.client.Minecraft.getInstance().player;

		if (player != null && player.blockPosition()
				.distSqr(new BlockPos(shared.x(), shared.y(), shared.z())) <= REACHED_SQ) {
			return;
		}

		Waypoint existing = find(shared.label());

		if (existing != null && existing.expiresAt() != 0) {
			ACTIVE.remove(existing);
		} else if (existing != null || ACTIVE.size() >= MAX_WAYPOINTS) {
			// A manual waypoint owns its name, and a full list stays as it is -
			// chat must never displace something the player placed on purpose.
			return;
		}

		// Born armed: creation already proved the player is not on the spot,
		// so a callout closer than the arming distance still cleans up on
		// arrival instead of lingering out its whole timer.
		ACTIVE.add(new Waypoint(shared.label(),
				new BlockPos(shared.x(), shared.y(), shared.z()),
				COLORS[nextColor++ % COLORS.length],
				System.currentTimeMillis() + SHARED_LIFETIME_MILLIS, true));
	}

	/**
	 * A waypoint placed by another feature (Crystal Hollows structures):
	 * replaces an earlier one under the same label, respects the cap, and
	 * lapses after its lifetime. Born armed, so standing near the spot
	 * still cleans it up on arrival.
	 */
	public static void place(String name, BlockPos pos, long lifetimeMillis) {
		Waypoint existing = find(name);

		if (existing != null) {
			ACTIVE.remove(existing);
		} else if (ACTIVE.size() >= MAX_WAYPOINTS) {
			return;
		}

		ACTIVE.add(new Waypoint(name, pos,
				COLORS[nextColor++ % COLORS.length],
				lifetimeMillis == 0 ? 0
						: System.currentTimeMillis() + lifetimeMillis, true));
	}

	private static Waypoint find(String name) {
		for (Waypoint waypoint : ACTIVE) {
			if (waypoint.name().equalsIgnoreCase(name)) {
				return waypoint;
			}
		}

		return null;
	}

	private static void say(FabricClientCommandSource source, Component message) {
		source.sendFeedback(Component.empty());
		source.sendFeedback(message);
		source.sendFeedback(Component.empty());
	}
}
