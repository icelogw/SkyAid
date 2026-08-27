package dev.skyaid.dungeon.core;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.skyaid.config.ConfigManager;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one place dungeon boxes and labels are drawn - the visual language of
 * focus mode. A bright box (crisp stroke, light fill) is reserved for the one
 * thing that matters now: the nearest undone secret, or a solver's "act here".
 * Everything else renders as a faint outline, so the screen never fills with
 * shouting.
 *
 * <p>The bright marker carries a floating label ("Chest - 12m"), rendered on
 * the same submitNameTag path the waypoint renderer proved out - parameter
 * order included, which once cost a debug round-trip.
 */
public final class MarkerRenderer {
	/** A labelled position: the focus secret or a solver instruction. */
	public record Tag(BlockPos pos, String label, int color) {
	}

	/** Solver instructions wear this; no secret kind does. */
	public static final int ACTION_COLOR = 0xFF3DDC;

	private static final int FULL_BRIGHT = 0xF000F0;
	private static final double TAG_SCALE_PER_BLOCKS = 12.0;

	/** How long a submitted action marker outlives its last tick. */
	private static final long ACTION_LIFETIME_MILLIS = 250;

	private static volatile Tag focus;

	private record ActionTag(Tag tag, long expiresAt) {
	}

	private static final List<ActionTag> actions = new CopyOnWriteArrayList<>();

	private MarkerRenderer() {
	}

	/**
	 * A labelled bright box in a caller-chosen colour, fading when the
	 * calls stop - the generic shape behind solver actions and pest marks.
	 */
	public static void point(BlockPos pos, String label, int color) {
		boxBright(pos, color);

		long now = System.currentTimeMillis();
		actions.removeIf(entry -> entry.expiresAt() < now
				|| entry.tag().pos().equals(pos));
		actions.add(new ActionTag(
				new Tag(pos, label, color), now + ACTION_LIFETIME_MILLIS));
	}

	/** The bright box: reserved for the focus marker and solver actions. */
	public static void boxBright(BlockPos pos, int color) {
		Gizmos.cuboid(pos, GizmoStyle.strokeAndFill(
						0xFF000000 | color, 2.0f, 0x18000000 | color))
				.persistForMillis(120)
				.setAlwaysOnTop();
	}

	/** The quiet box: a thin, translucent outline with no fill. */
	public static void boxDim(BlockPos pos, int color) {
		Gizmos.cuboid(pos, GizmoStyle.strokeAndFill(
						0x66000000 | color, 1.0f, 0x00000000))
				.persistForMillis(120)
				.setAlwaysOnTop();
	}

	/** Sets (or clears, with null) the labelled focus marker. */
	public static void setFocus(Tag tag) {
		focus = tag;
	}

	/**
	 * A solver's "act here now": bright action-coloured box plus label. Call
	 * every solver tick; the marker fades by itself when the calls stop.
	 */
	public static void action(BlockPos pos, String label) {
		point(pos, label, ACTION_COLOR);
	}

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			if (!ConfigManager.get().enabled) {
				return;
			}

			long now = System.currentTimeMillis();
			actions.removeIf(entry -> entry.expiresAt() < now);

			Tag focusTag = focus;

			if (focusTag == null && actions.isEmpty()) {
				return;
			}

			CameraRenderState camera = context.levelState().cameraRenderState;

			if (camera == null || !camera.initialized) {
				return;
			}

			PoseStack pose = context.poseStack();
			var collector = context.submitNodeCollector();

			if (focusTag != null) {
				submitTag(pose, collector, camera, focusTag);
			}

			for (ActionTag action : actions) {
				submitTag(pose, collector, camera, action.tag());
			}
		});
	}

	private static void submitTag(PoseStack pose,
			net.minecraft.client.renderer.SubmitNodeCollector collector,
			CameraRenderState camera, Tag tag) {
		double dx = tag.pos().getX() - camera.pos.x;
		double dy = tag.pos().getY() - camera.pos.y;
		double dz = tag.pos().getZ() - camera.pos.z;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		// The tag grows with distance so it stays readable across the room.
		float tagScale = (float) Math.min(3.0,
				Math.max(1.0, distance / TAG_SCALE_PER_BLOCKS));

		// Parameter order verified against the disassembly: the int before
		// the text is a pixel offset, and the light value comes after the
		// see-through flag - swapping them once drew the tag 400k blocks
		// underground.
		pose.pushPose();
		pose.translate(dx + 0.5, dy + 1.4, dz + 0.5);
		pose.scale(tagScale, tagScale, tagScale);
		collector.submitNameTag(pose, Vec3.ZERO, 0,
				Component.literal(tag.label())
						.append(Component.literal("  " + Math.round(distance) + "m")
								.withStyle(ChatFormatting.GRAY)),
				true, FULL_BRIGHT, camera);
		pose.popPose();
	}
}
