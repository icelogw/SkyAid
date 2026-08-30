package dev.skyaid.feature;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.parse.GardenLines;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * A tripod for the camera: {@code /skyaid mouselock} freezes the look angle
 * (mouse movement stops turning the player until released), and
 * {@code /skyaid mouselock <yaw> <pitch>} sets an exact angle first - lining
 * up a farming row once and never drifting off it.
 *
 * <p>Three preset slots remember angles across sessions:
 * {@code /skyaid mouselock save <1-3>} stores the current camera, and each
 * slot has its own key in Controls - press it to snap-and-lock to that
 * angle, press it again to release. Two rows, two keys.
 *
 * <p>The rules line this walks: the lock SUPPRESSES the player's own camera
 * input, it never generates any - no clicks, no movement, no aim toward
 * anything. A preset key is one deterministic camera set per press, in the
 * same spirit as the F1/F2 market keys.
 * Movement keys are untouched.
 */
public final class MouseLock {
	private static volatile boolean locked;

	/** Which preset the current lock came from; 0 for a manual lock. */
	private static int activeSlot;

	/** The preset slot held by a key right now; 0 when no key holds one. */
	private static int heldSlot;

	/** Degrees per tick while gliding to a preset - a quick, human turn. */
	private static final float GLIDE_STEP = 25.0f;

	private static volatile boolean gliding;
	private static float targetYaw;
	private static float targetPitch;

	/** The level the lock was engaged in; a change releases it. */
	private static Object lastLevel;

	/**
	 * Holding a preset key fires OS key-repeats, and every repeat toggled
	 * the lock - the on/off flashing and the chat spam were both this. One
	 * action, then a deaf window.
	 */
	private static final long HINT_DEBOUNCE_MILLIS = 10_000;

	private static long lastHintAt;

	/**
	 * When a "/skyaid mouselock set" preview lock frees itself; zero while
	 * no preview is running. The timer keeps the hold-only promise: even
	 * this command lock cannot outlive the moment - it exists just long
	 * enough to SHOW the angle.
	 */
	private static final long PREVIEW_MILLIS = 3_000;
	private static long previewUntil;

	private MouseLock() {
	}

	/** Read by the mixin every mouse-turn, and by the HUD's angle line. */
	public static boolean locked() {
		return locked;
	}

	/**
	 * The active preset group, migrating the legacy flat list into a
	 * "Default" group the first time and clamping the active index.
	 */
	public static dev.skyaid.config.Config.MouseLockGroup group() {
		var config = ConfigManager.get();

		if (config.mouseLockGroups.isEmpty()) {
			var seed = new dev.skyaid.config.Config.MouseLockGroup();
			seed.angles = new java.util.ArrayList<>(config.mouseLockPresets);
			config.mouseLockGroups.add(seed);
		}

		config.mouseLockActiveGroup = Math.max(0, Math.min(
				config.mouseLockGroups.size() - 1, config.mouseLockActiveGroup));
		return config.mouseLockGroups.get(config.mouseLockActiveGroup);
	}

	public static void register() {
		// The always-visible reminder: red text just above the action bar
		// while the lock holds, so a stuck camera is never a mystery.
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
				net.minecraft.resources.Identifier.fromNamespaceAndPath(
						dev.skyaid.SkyAidClient.MOD_ID, "mouse_lock"),
				(extractor, deltaTracker) -> {
					if (!locked) {
						return;
					}

					var font = Minecraft.getInstance().font;
					String label = activeSlot > 0
							? "MOUSE LOCKED (preset " + activeSlot + ")"
							: previewUntil != 0
									? "MOUSE LOCKED (preview)"
									: "MOUSE LOCKED";
					extractor.text(font, label,
							extractor.guiWidth() / 2 - font.width(label) / 2,
							extractor.guiHeight() - 84, 0xFFFF5555, true);
				});

		// The glide toward a preset angle, one capped step per tick; the
		// lock already holds the mouse, so the turn is uncontested.
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
				.register(client -> {
					// A warp is a new level: the lock releases itself so the
					// camera is never mysteriously frozen somewhere else.
					if (client.level != lastLevel) {
						lastLevel = client.level;

						if (locked) {
							release();
						}
					}

					// A "set" preview frees itself once its 3 seconds are up.
					if (locked && previewUntil != 0
							&& System.currentTimeMillis() >= previewUntil) {
						release();
					}

					if (!gliding || client.player == null) {
						return;
					}

					float yawDelta = wrapDegrees(targetYaw - client.player.getYRot());
					float pitchDelta = targetPitch - client.player.getXRot();

					if (Math.abs(yawDelta) <= GLIDE_STEP
							&& Math.abs(pitchDelta) <= GLIDE_STEP) {
						client.player.setYRot(targetYaw);
						client.player.setXRot(targetPitch);
						gliding = false;
						return;
					}

					client.player.setYRot(client.player.getYRot()
							+ clampStep(yawDelta));
					client.player.setXRot(client.player.getXRot()
							+ clampStep(pitchDelta));
				});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("skyaid")
						.then(ClientCommands.literal("mouselock")
								.executes(context -> {
									// Bare command = the menu; locking is the
									// hold-keys' and on/off's job.
									Minecraft.getInstance().execute(() ->
											Minecraft.getInstance().setScreenAndShow(
													new MouseLockScreen()));
									return 1;
								})
								.then(ClientCommands.literal("on").executes(context -> {
									setEnabled(true);
									return 1;
								}))
								.then(ClientCommands.literal("off").executes(context -> {
									setEnabled(false);
									return 1;
								}))
								.then(ClientCommands.literal("save")
										.then(ClientCommands.argument("slot",
														IntegerArgumentType.integer(1, 6))
												.executes(context -> {
													savePreset(IntegerArgumentType
															.getInteger(context, "slot"));
													return 1;
												})))
								.then(ClientCommands.literal("set")
										.then(ClientCommands.argument("yaw",
														FloatArgumentType.floatArg(-360, 360))
												.then(ClientCommands.argument("pitch",
																FloatArgumentType.floatArg(-90, 90))
														.executes(context -> {
															previewAt(FloatArgumentType
																			.getFloat(context, "yaw"),
																	FloatArgumentType.getFloat(
																			context, "pitch"));
															return 1;
														}))))
								)));
		// No standing command locks: mouse lock is hold-only by design, so
		// the camera is never locked while nothing is physically held. The
		// one exception is "set", a PREVIEW that frees itself after 3s.
	}

	/**
	 * Hold-to-lock, fed every tick with the three preset keys' held
	 * state: first held key wins, holding glides-and-locks to its slot,
	 * releasing frees the camera.
	 */
	public static void holdTick(boolean... held) {
		if (!enabled()) {
			if (heldSlot != 0) {
				heldSlot = 0;
				release();
			}

			return;
		}

		int wanted = 0;

		for (int i = 0; i < held.length; i++) {
			if (held[i]) {
				wanted = i + 1;
				break;
			}
		}

		if (wanted == heldSlot) {
			return;
		}

		if (wanted == 0) {
			heldSlot = 0;
			release();
			return;
		}

		List<Float> presets = group().angles;

		if (presets.size() < wanted * 2 || presets.get(wanted * 2 - 2) == null
				|| presets.get(wanted * 2 - 1) == null) {
			long now = System.currentTimeMillis();

			if (now - lastHintAt >= HINT_DEBOUNCE_MILLIS) {
				lastHintAt = now;
				say(Component.literal("Preset " + wanted
								+ " is empty - /skyaid mouselock save " + wanted)
						.withStyle(ChatFormatting.GRAY));
			}

			return;
		}

		lockAt(presets.get(wanted * 2 - 2), presets.get(wanted * 2 - 1));
		activeSlot = wanted;
		heldSlot = wanted;
	}

	public static void toggle() {
		if (!enabled()) {
			say(Component.literal("Mouse lock is off - /skyaid mouselock on")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		setLocked(!locked);
	}

	/** The enable keybind: flips the same master switch as on/off. */
	public static void toggleEnabled() {
		setEnabled(!ConfigManager.get().mouseLockEnabled);
	}

	/** The whole feature's master switch: off releases and goes inert. */
	private static void setEnabled(boolean value) {
		ConfigManager.get().mouseLockEnabled = value;
		ConfigManager.save();

		if (!value) {
			release();
		}

		say(Component.literal(value ? "Mouse lock enabled." : "Mouse lock disabled.")
				.withStyle(value ? ChatFormatting.GREEN : ChatFormatting.GRAY));
	}

	private static boolean enabled() {
		return ConfigManager.get().enabled && ConfigManager.get().mouseLockEnabled;
	}

	/** Stores the current camera angle in a slot, kept across sessions. */
	public static void savePreset(int slot) {
		var player = Minecraft.getInstance().player;

		if (player == null) {
			return;
		}

		List<Float> presets = group().angles;

		while (presets.size() < slot * 2) {
			presets.add(null);
		}

		presets.set(slot * 2 - 2, player.getYRot());
		presets.set(slot * 2 - 1, player.getXRot());
		ConfigManager.save();

		say(Component.literal(String.format(java.util.Locale.ROOT,
						"Preset %d saved (Yaw %.1f, Pitch %.1f). ",
						slot, player.getYRot(), player.getXRot()))
				.withStyle(ChatFormatting.GREEN)
				.append(Component.literal("Key: Options > Controls > SkyAid.")
						.withStyle(ChatFormatting.GRAY)));
	}

	/** Every path out of a lock resets the same four fields. */
	private static void release() {
		locked = false;
		activeSlot = 0;
		gliding = false;
		previewUntil = 0;
	}

	private static void setLocked(boolean value) {
		// Silent by design: the red banner is the feedback, not chat.
		if (value) {
			locked = true;
			activeSlot = 0;
			gliding = false;
			previewUntil = 0;
		} else {
			release();
		}
	}

	/**
	 * One explicit command or preset key starts a GLIDE to the angle - a
	 * capped per-tick turn, the shape of a fast manual flick - never an
	 * instant snap: a one-tick 180 in the movement packet is exactly what
	 * anticheat heuristics flag.
	 */
	private static void lockAt(float yaw, float pitch) {
		var player = Minecraft.getInstance().player;

		if (player == null) {
			return;
		}

		if (!enabled()) {
			return;
		}

		targetYaw = yaw;
		targetPitch = pitch;
		gliding = true;
		locked = true;
		activeSlot = 0;
		previewUntil = 0;
	}

	/**
	 * "/skyaid mouselock set yaw pitch": glides to the angle and holds it
	 * for 3 seconds so the aim can be SEEN, then frees the camera itself -
	 * a look, not a standing lock.
	 */
	private static void previewAt(float yaw, float pitch) {
		if (!enabled()) {
			say(Component.literal("Mouse lock is off - /skyaid mouselock on")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		lockAt(yaw, pitch);

		if (locked) {
			previewUntil = System.currentTimeMillis() + PREVIEW_MILLIS;
		}
	}

	/** The shortest signed way around the circle to the target yaw. */
	private static float wrapDegrees(float degrees) {
		float wrapped = degrees % 360;

		if (wrapped >= 180) {
			wrapped -= 360;
		}

		if (wrapped < -180) {
			wrapped += 360;
		}

		return wrapped;
	}

	private static float clampStep(float delta) {
		return Math.max(-GLIDE_STEP, Math.min(GLIDE_STEP, delta));
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
