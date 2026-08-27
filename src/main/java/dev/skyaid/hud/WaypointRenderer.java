package dev.skyaid.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.feature.Waypoints;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Draws each waypoint as a real beacon beam - vanilla's own renderer, tinted
 * the waypoint's colour - with a floating name-and-distance tag, so a marked
 * spot is findable from across the island.
 *
 * <p>Pure rendering on the client's own view, of positions the player or a
 * teammate chose themselves.
 */
public final class WaypointRenderer {
	/** How far the beam reaches above the waypoint - the whole sky column. */
	private static final int BEAM_HEIGHT = 1024;

	/** Vanilla's own beam proportions, matching a placed beacon exactly. */
	private static final float BEAM_RADIUS = 0.2f;
	private static final float GLOW_RADIUS = 0.25f;

	/** Full-bright packed light, so tags read at night and in caves. */
	private static final int FULL_BRIGHT = 0xF000F0;

	/**
	 * This close, the marker hides - it has done its job and would only be in
	 * the way. A sphere, so a floor above or below still counts as far. Sized
	 * from a screenshot of where standing there expecting it gone: two blocks
	 * beside the spot, which is just over 2.0 to the block's centre.
	 */
	private static final double HIDE_NEAR_DISTANCE = 3.0;

	/**
	 * The tag grows with distance so it stays readable; past this many blocks
	 * per unit of extra scale it would just be billboard-sized noise.
	 */
	private static final double TAG_SCALE_PER_BLOCKS = 12.0;

	private WaypointRenderer() {
	}

	public static void register() {
		// Inside the Catacombs the marker is a glowing box drawn through walls:
		// the dungeon is an enclosed maze, so a sky beam would be hidden behind
		// the next wall. Gizmos are re-added every tick and persist just past
		// the next one, which keeps them continuous without lifetime tracking.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ConfigManager.get().enabled || !ConfigManager.get().waypointBeams) {
				return;
			}

			if (client.level == null || client.player == null) {
				return;
			}

			// Debug mode lifts the Catacombs gate, as everywhere, so the box can
			// be seen in the offline dev client.
			if (!SkyblockTracker.state().inCatacombs() && !ConfigManager.get().debug) {
				return;
			}

			long maxDistance = ConfigManager.get().waypointRenderDistanceClamped();

			for (Waypoints.Waypoint waypoint : Waypoints.all()) {
				// A spherical near-check, so height counts as distance: standing
				// a floor above the marker is not "at" it, and the box stays.
				double distSq = client.player.position().distanceToSqr(
						waypoint.pos().getX() + 0.5,
						waypoint.pos().getY() + 0.5,
						waypoint.pos().getZ() + 0.5);

				if (distSq < HIDE_NEAR_DISTANCE * HIDE_NEAR_DISTANCE
						|| distSq > maxDistance * maxDistance) {
					continue;
				}

				// A crisp outline with only a light tint of fill - a solid box
				// hid whatever was inside it, which is the thing being marked.
				dev.skyaid.dungeon.core.MarkerRenderer.boxBright(
						waypoint.pos(), waypoint.color());
			}
		});

		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			if (!ConfigManager.get().enabled || !ConfigManager.get().waypointBeams) {
				return;
			}

			var waypoints = Waypoints.all();

			if (waypoints.isEmpty()) {
				return;
			}

			CameraRenderState camera = context.levelState().cameraRenderState;

			if (camera == null || !camera.initialized) {
				return;
			}

			PoseStack pose = context.poseStack();
			var collector = context.submitNodeCollector();

			// Wall-clock based so the beam's slow spin stays smooth; the exact
			// phase is cosmetic, so wrap-around every hour is harmless.
			float animationTime = (System.currentTimeMillis() % 3_600_000L) / 50.0f;

			int maxDistance = ConfigManager.get().waypointRenderDistanceClamped();

			// Indoors the glowing box above replaces the beam; the see-through
			// tag below still renders in both worlds.
			boolean enclosed = SkyblockTracker.state().inCatacombs();

			var player = net.minecraft.client.Minecraft.getInstance().player;

			for (Waypoints.Waypoint waypoint : waypoints) {
				double dx = waypoint.pos().getX() - camera.pos.x;
				double dy = waypoint.pos().getY() - camera.pos.y;
				double dz = waypoint.pos().getZ() - camera.pos.z;

				double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

				// The same spherical near-check as the dungeon box, measured from
				// the player to the block's centre, so beam and box hide at
				// exactly the same spot. The range cap uses the camera distance.
				double nearSq = player == null ? Double.MAX_VALUE
						: player.position().distanceToSqr(
								waypoint.pos().getX() + 0.5,
								waypoint.pos().getY() + 0.5,
								waypoint.pos().getZ() + 0.5);

				if (nearSq < HIDE_NEAR_DISTANCE * HIDE_NEAR_DISTANCE
						|| distance > maxDistance) {
					continue;
				}

				if (!enclosed) {
					pose.pushPose();
					pose.translate(dx, dy, dz);
					BeaconRenderer.submitBeaconBeam(pose, collector,
							BeaconRenderer.BEAM_LOCATION, 1.0f, animationTime,
							0, BEAM_HEIGHT, 0xFF000000 | waypoint.color(),
							BEAM_RADIUS, GLOW_RADIUS);
					pose.popPose();
				}

				// Parameter order verified against the disassembly: the int before
				// the text is a pixel offset, and the light value comes after the
				// see-through flag - swapping them once drew the tag 400k blocks
				// underground.
				//
				// The tag scales with distance so it reads from across the island
				// instead of shrinking to a speck.
				// Capped: uncapped it grew to billboard size across the island
				// and covered half the screen.
				float tagScale = (float) Math.min(3.0,
						Math.max(1.0, distance / TAG_SCALE_PER_BLOCKS));

				pose.pushPose();
				pose.translate(dx + 0.5, dy + 1.6, dz + 0.5);
				pose.scale(tagScale, tagScale, tagScale);
				collector.submitNameTag(pose, Vec3.ZERO, 0,
						Component.literal(waypoint.name())
								.append(Component.literal("  " + Math.round(distance) + "m")
										.withStyle(ChatFormatting.GRAY)),
						true, FULL_BRIGHT, camera);
				pose.popPose();
			}
		});
	}
}
