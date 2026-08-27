package dev.skyaid.dungeon.core;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.dungeon.rooms.RoomData;
import dev.skyaid.parse.RoomMath;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The one model of "what secrets exist here and which are done" - the database
 * predicts, the world verifies. In an identified room the markers are exactly
 * the recorded secrets; in an unknown room a fallback scan offers only what it
 * is CERTAIN of: chests, essence skulls with a learned texture, and lone
 * unpowered levers. No guessing - every heuristic that ever produced a wrong
 * box is gone.
 *
 * <p>Markers retire on verifiable signals only, arm-then-retire so a
 * slightly-off recorded position can never delete itself before its object was
 * ever seen: a clicked chest, a vanished head or wall block, a powered lever,
 * or - for bats, items and fairy souls - the player reaching the spot. When
 * Hypixel's own action bar says the room's count is complete, everything left
 * retires at once.
 *
 * <p>Observation only: clicks are watched (and always passed through), never
 * made.
 */
public final class SecretsBoard {
	/** The kinds of secret, each with its label and colour. */
	public enum Kind {
		CHEST("Chest", 0xFFC93C),
		ESSENCE("Essence", 0xB266FF),
		LEVER("Lever", 0x4DA6FF),
		BAT("Bat", 0x35E0C8),
		WALL("Hidden wall", 0xFF8A3D),
		ITEM("Item", 0xE0E0E0),
		SOUL("Fairy soul", 0xFF9BD6);

		final String label;
		final int color;

		Kind(String label, int color) {
			this.label = label;
			this.color = color;
		}

		public String label() {
			return label;
		}

		public int color() {
			return color;
		}
	}

	/** One secret marker and its life story. */
	public static final class Marker {
		private final BlockPos pos;
		private final Kind kind;
		private boolean armed;
		private boolean done;

		Marker(BlockPos pos, Kind kind) {
			this.pos = pos;
			this.kind = kind;
		}

		public BlockPos pos() {
			return pos;
		}

		public Kind kind() {
			return kind;
		}

		public boolean done() {
			return done;
		}
	}

	private static final int TICK_INTERVAL = 10;

	/** Fallback scanning reach in unknown rooms; roughly one room. */
	private static final int SCAN_RADIUS = 20;

	/** Ticks a clicked head gets to disappear before the click is forgotten. */
	private static final int HEAD_VERDICT_TICKS = 40;

	/** Built-in essence textures; learned ones live in the config list. */
	private static final Set<String> ESSENCE_TEXTURES = Set.of();

	private record PendingHead(BlockPos pos, String hash, long deadline) {
	}

	private static int tickCounter;
	private static long tickClock;

	/**
	 * Database markers for every room identified this run, keyed by room cell
	 * key. Rooms KEEP their markers after the player walks out - with the
	 * range setting at 100 blocks, a solved room's leftovers stay visible
	 * through the wall, which is the point of through-wall boxes.
	 */
	private static final java.util.Map<String, List<Marker>> byRoom =
			new java.util.LinkedHashMap<>();

	/** Which room key the player is currently in. */
	private static String currentKeyMarkers = "";

	/**
	 * Whether the action bar has shown an UNFINISHED secrets count for the
	 * current room. The completion sweep requires it: entering a new room,
	 * the bar still shows the previous room's completed count for a few
	 * ticks, and sweeping on that stale reading retired a whole room's
	 * markers before they were ever seen (caught in the field: HUD said 3/3
	 * while Hypixel said 0/1).
	 */
	private static boolean sawIncompleteCount;

	/** Fallback-scan markers for the current UNKNOWN room only. */
	private static List<Marker> fallback = List.of();

	/**
	 * The fallback scan is SLICED: two horizontal layers of the cube per
	 * tick, a full cycle every second or so. The old all-at-once sweep read
	 * ~69k blocks in one tick twice a second - a felt stutter every time a
	 * room was unidentified, which is most of the time while walking.
	 */
	private static BlockPos fallbackCentre;
	private static int fallbackLayer;
	private static final List<Marker> fallbackBuilding = new ArrayList<>();
	private static final List<BlockPos> fallbackLevers = new ArrayList<>();

	/** Chests the player already opened, so no source re-marks them. */
	private static final Set<BlockPos> openedChests = new LinkedHashSet<>();

	private static final List<PendingHead> pendingHeads = new ArrayList<>();

	private SecretsBoard() {
	}

	/** All live markers - every identified room's plus the fallback scan's. */
	public static List<Marker> markers() {
		if (byRoom.isEmpty()) {
			return fallback;
		}

		List<Marker> all = new ArrayList<>();

		for (List<Marker> roomMarkers : byRoom.values()) {
			all.addAll(roomMarkers);
		}

		all.addAll(fallback);
		return all;
	}

	/** The current room's markers, or empty when it is not identified. */
	private static List<Marker> currentRoomMarkers() {
		return byRoom.getOrDefault(currentKeyMarkers, List.of());
	}

	/** "Room name - Secrets 2/5" for the HUD, once the room is identified. */
	public static Optional<String> hudLine() {
		List<Marker> current = currentRoomMarkers();

		if (current.isEmpty()) {
			return Optional.empty();
		}

		Optional<DungeonTracker.Room> room = DungeonTracker.currentRoom();

		if (room.isEmpty()) {
			return Optional.empty();
		}

		long done = current.stream().filter(Marker::done).count();
		return Optional.of(room.get().name() + " - Secrets "
				+ done + "/" + current.size());
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ConfigManager.get().enabled || !ConfigManager.get().secretMarkers) {
				return;
			}

			if (client.level == null || client.player == null) {
				return;
			}

			if (!SkyblockTracker.state().inCatacombs() && !ConfigManager.get().debug) {
				if (!byRoom.isEmpty() || !fallback.isEmpty() || !openedChests.isEmpty()
						|| !pendingHeads.isEmpty() || !currentKeyMarkers.isEmpty()) {
					byRoom.clear();
					cellsByRoom.clear();
					fallback = List.of();
					fallbackCentre = null;
					currentKeyMarkers = "";
					openedChests.clear();
					pendingHeads.clear();
				}

				return;
			}

			tickClock++;
			judgePendingHeads(client);

			if (++tickCounter >= TICK_INTERVAL) {
				tickCounter = 0;
				rebuildIfRoomChanged(client);
				retire(client);
			}

			// The certain-scan fallback advances a slice every tick while the
			// room is unknown; known rooms use database markers only.
			if (DungeonTracker.currentRoom().isEmpty()) {
				fallbackTick(client);
			}

			render(client);
		});

		// A looted chest never changes as a block, so the player's own click
		// is what retires its marker; a clicked player head goes on trial -
		// vanishing convicts it of being an essence skull, and its texture is
		// learned for good. The click always passes through untouched.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!ConfigManager.get().enabled || !ConfigManager.get().secretMarkers
					|| (!SkyblockTracker.state().inCatacombs()
							&& !ConfigManager.get().debug)) {
				return InteractionResult.PASS;
			}

			BlockPos pos = hit.getBlockPos();
			Block block = level.getBlockState(pos).getBlock();

			if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
				openedChests.add(pos.immutable());

				for (Marker marker : markers()) {
					if (marker.kind == Kind.CHEST && !marker.done
							&& marker.pos.distSqr(pos) <= 2 * 2) {
						marker.done = true;
					}
				}
			} else if (block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD) {
				skinHash(level, pos).ifPresent(hash -> pendingHeads.add(
						new PendingHead(pos.immutable(), hash,
								tickClock + HEAD_VERDICT_TICKS)));
			}

			return InteractionResult.PASS;
		});
	}

	/**
	 * Keeps the per-room marker map current. An identified room's markers are
	 * built once and then KEPT for the whole run - walking out does not hide
	 * another room's secrets, range does. Only an unknown room falls back to
	 * scanning, and only while the player is in it.
	 */
	private static void rebuildIfRoomChanged(Minecraft client) {
		String key = DungeonTracker.currentKey();

		if (!key.equals(currentKeyMarkers)) {
			sawIncompleteCount = false;
			// The previous room's fallback guesses must not linger into this
			// one; a fresh cycle starts from scratch.
			fallback = List.of();
			fallbackCentre = null;
		}

		currentKeyMarkers = key;
		Optional<DungeonTracker.Room> room = DungeonTracker.currentRoom();

		if (room.isEmpty()) {
			return; // The sliced fallback scan covers unknown rooms per tick.
		}

		// Known room: the fallback's guesses stand down for the data.
		fallback = List.of();
		fallbackCentre = null;

		if (byRoom.containsKey(currentKeyMarkers)) {
			return;
		}

		List<Marker> built = new ArrayList<>();

		for (RoomData.Secret secret : RoomData.secretsFor(room.get().name())) {
			Kind kind = kindOf(secret.category());

			if (kind == null) {
				continue;
			}

			int[] world = RoomMath.relativeToActual(secret.x(), secret.y(),
					secret.z(), room.get().direction(), room.get().corner());
			built.add(new Marker(
					new BlockPos(world[0], world[1], world[2]), kind));
		}

		byRoom.put(currentKeyMarkers, built);
		cellsByRoom.put(currentKeyMarkers, room.get().cells());
	}

	private static Kind kindOf(String category) {
		return switch (category) {
			case "chest" -> Kind.CHEST;
			case "wither" -> Kind.ESSENCE;
			case "lever" -> Kind.LEVER;
			case "bat" -> Kind.BAT;
			case "superboom", "stonk" -> Kind.WALL;
			case "item" -> Kind.ITEM;
			case "fairysoul" -> Kind.SOUL;
			default -> null; // entrance and in-room puzzle markers: not drawn
		};
	}

	/**
	 * Unknown room: offer only certainties. Chests and essence skulls ARE
	 * their secret; a lone unpowered lever almost always is. Two horizontal
	 * layers of the scan cube advance per tick; when the cycle completes, the
	 * result replaces the previous one - so a wrong guess cannot linger, and
	 * no single tick pays for the whole cube.
	 */
	private static void fallbackTick(Minecraft client) {
		if (fallbackCentre == null) {
			fallbackCentre = client.player.blockPosition();
			fallbackLayer = 0;
			fallbackBuilding.clear();
			fallbackLevers.clear();
		}

		var cursor = new BlockPos.MutableBlockPos();

		for (int slice = 0; slice < 2 && fallbackLayer <= SCAN_RADIUS * 2;
				slice++, fallbackLayer++) {
			int y = fallbackCentre.getY() - SCAN_RADIUS + fallbackLayer;

			for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
				for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
					cursor.set(fallbackCentre.getX() + x, y, fallbackCentre.getZ() + z);
					var state = client.level.getBlockState(cursor);
					Block block = state.getBlock();

					if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
						if (!openedChests.contains(cursor)) {
							fallbackBuilding.add(new Marker(cursor.immutable(), Kind.CHEST));
						}
					} else if (block == Blocks.PLAYER_HEAD
							|| block == Blocks.PLAYER_WALL_HEAD) {
						if (skinHash(client.level, cursor)
								.map(SecretsBoard::isEssence).orElse(false)) {
							fallbackBuilding.add(new Marker(cursor.immutable(), Kind.ESSENCE));
						}
					} else if (block == Blocks.LEVER) {
						if (!state.getValueOrElse(BlockStateProperties.POWERED, false)) {
							fallbackLevers.add(cursor.immutable());
						}
					}
				}
			}
		}

		if (fallbackLayer <= SCAN_RADIUS * 2) {
			return; // Cycle still in progress; results land when it finishes.
		}

		// A secret lever stands alone; several in one cycle is machinery.
		if (fallbackLevers.size() <= 2) {
			for (BlockPos lever : fallbackLevers) {
				fallbackBuilding.add(new Marker(lever, Kind.LEVER));
			}
		}

		// Carry over done-flags for positions that persist between cycles.
		for (Marker previous : fallback) {
			if (previous.done) {
				for (Marker marker : fallbackBuilding) {
					if (marker.pos.equals(previous.pos)) {
						marker.done = true;
					}
				}
			}
		}

		fallback = List.copyOf(fallbackBuilding);
		fallbackCentre = null;
	}

	/** Retires markers whose secret has verifiably been collected. */
	private static void retire(Minecraft client) {
		BlockPos player = client.player.blockPosition();

		// Hypixel's own word beats every inference: the action bar's count is
		// the CURRENT room's, so it retires that room's leftovers - but only
		// once an unfinished count has been seen in THIS room, or a stale
		// completed count carried over from the last room sweeps a fresh one.
		var bar = SkyblockTracker.actionBar();

		if (bar.secretsTotal().isPresent() && bar.secretsTotal().getAsLong() > 0) {
			long found = bar.secretsFound().orElse(-1);
			long total = bar.secretsTotal().getAsLong();

			if (found >= 0 && found < total) {
				sawIncompleteCount = true;
			} else if (found == total && sawIncompleteCount) {
				for (Marker marker : currentRoomMarkers()) {
					marker.done = true;
				}
			}
		}

		for (Marker marker : markers()) {
			if (marker.done || player.distSqr(marker.pos) > 32 * 32) {
				continue;
			}

			// Arm-then-retire protects a slightly-off recorded position from
			// deleting itself sight-unseen - but a secret collected BEFORE the
			// player first came near never arms, and its marker would live
			// forever. Standing close is proof enough: the chunk is loaded,
			// the object is plainly absent, so the marker retires unarmed too.
			// Chests get only a point-blank version of that mercy: many only
			// SPAWN mid-chain (after a lever or wall), so an absent chest at
			// eight blocks usually means "not yet", not "already taken".
			boolean pointBlank = player.distSqr(marker.pos) <= 8 * 8;
			boolean onTopOfIt = player.distSqr(marker.pos) <= 2 * 2;

			switch (marker.kind) {
				case CHEST -> {
					if (chestNear(client, marker.pos)) {
						marker.armed = true;
					} else if (marker.armed || onTopOfIt) {
						marker.done = true;
					}
				}
				case ESSENCE -> {
					if (headNear(client, marker.pos)) {
						marker.armed = true;
					} else if (marker.armed || pointBlank) {
						marker.done = true;
					}
				}
				case LEVER -> {
					var state = client.level.getBlockState(marker.pos);

					if (state.getBlock() == Blocks.LEVER) {
						marker.armed = true;

						if (state.getValueOrElse(BlockStateProperties.POWERED, false)) {
							marker.done = true;
						}
					} else if (marker.armed || pointBlank) {
						marker.done = true;
					}
				}
				case WALL -> {
					// A dig spot is an approach hint, not a collectible:
					// standing at it means you are through - by pickaxe,
					// superboom, or a ladder around the back - so arriving
					// retires it even while its block stands.
					if (onTopOfIt) {
						marker.done = true;
					} else if (!client.level.getBlockState(marker.pos).isAir()) {
						marker.armed = true;
					} else if (marker.armed || pointBlank) {
						marker.done = true;
					}
				}
				case BAT -> {
					if (player.distSqr(marker.pos) <= 6 * 6) {
						marker.done = true;
					}
				}
				case ITEM, SOUL -> {
					if (player.distSqr(marker.pos) <= 3 * 3) {
						marker.done = true;
					}
				}
			}
		}

		retireCompanions();
	}

	/**
	 * A wall or item marker within arm's reach of a completed chest or skull
	 * is the same secret's approach - the dig spot in front of the alcove -
	 * and retires with it. Its own block often stays solid forever (players
	 * reach the chest without digging), so it has no signal of its own.
	 */
	private static void retireCompanions() {
		List<Marker> all = markers();

		for (Marker completed : all) {
			if (!completed.done
					|| (completed.kind != Kind.CHEST && completed.kind != Kind.ESSENCE)) {
				continue;
			}

			for (Marker companion : all) {
				if (!companion.done
						&& (companion.kind == Kind.WALL || companion.kind == Kind.ITEM)
						&& companion.pos.distSqr(completed.pos) <= 3 * 3) {
					companion.done = true;
				}
			}
		}
	}

	/**
	 * Other rooms' leftovers only show this close - just through the nearest
	 * wall. A whole run's worth of distant outlines read as markers that
	 * never go away, so the full range setting now applies
	 * only to the room the player is actually in.
	 */
	private static final int NEIGHBOUR_RANGE = 24;

	/** Focus mode: the nearest live marker shouts, the rest whisper. */
	private static void render(Minecraft client) {
		List<Marker> local = new ArrayList<>(currentRoomMarkers());
		local.addAll(fallback);

		long range = ConfigManager.get().waypointRenderDistanceClamped();
		long rangeSq = range * range;
		BlockPos player = client.player.blockPosition();

		// The bright guide box belongs to the room the player is in - focus
		// stealing across rooms pointed through walls that cannot be passed.
		Marker focus = null;
		double focusDistSq = Double.MAX_VALUE;

		for (Marker marker : local) {
			if (marker.done) {
				continue;
			}

			double distSq = player.distSqr(marker.pos);

			if (distSq <= rangeSq && distSq < focusDistSq) {
				focus = marker;
				focusDistSq = distSq;
			}
		}

		// A draw cap keeps a wide view from becoming a wall of outlines.
		int drawn = 0;

		for (Marker marker : local) {
			if (marker.done || player.distSqr(marker.pos) > rangeSq) {
				continue;
			}

			if (drawn++ >= 48) {
				break;
			}

			if (marker == focus) {
				MarkerRenderer.boxBright(marker.pos, marker.kind.color);
			} else {
				MarkerRenderer.boxDim(marker.pos, marker.kind.color);
			}
		}

		long neighbourSq = (long) NEIGHBOUR_RANGE * NEIGHBOUR_RANGE;

		for (var entry : byRoom.entrySet()) {
			if (entry.getKey().equals(currentKeyMarkers)) {
				continue;
			}

			for (Marker marker : entry.getValue()) {
				if (!marker.done && drawn < 48
						&& player.distSqr(marker.pos) <= neighbourSq) {
					drawn++;
					MarkerRenderer.boxDim(marker.pos, marker.kind.color);
				}
			}
		}

		MarkerRenderer.setFocus(focus == null ? null
				: new MarkerRenderer.Tag(focus.pos, focus.kind.label, focus.kind.color));
	}

	/** One identified room's map summary: its cells and secret counts. */
	public record RoomSummary(List<dev.skyaid.parse.RoomMath.Cell> cells, int done,
			int total) {
	}

	/** The cells of every identified room, kept for the map overlay. */
	private static final java.util.Map<String, List<dev.skyaid.parse.RoomMath.Cell>>
			cellsByRoom = new java.util.LinkedHashMap<>();

	/** Per-room done/total for every room identified this run. */
	public static List<RoomSummary> summaries() {
		List<RoomSummary> out = new ArrayList<>();

		for (var entry : byRoom.entrySet()) {
			List<dev.skyaid.parse.RoomMath.Cell> cells = cellsByRoom.get(entry.getKey());

			if (cells == null || entry.getValue().isEmpty()) {
				continue;
			}

			int done = 0;

			for (Marker marker : entry.getValue()) {
				if (marker.done) {
					done++;
				}
			}

			out.add(new RoomSummary(cells, done, entry.getValue().size()));
		}

		return out;
	}

	private static boolean isEssence(String hash) {
		return ESSENCE_TEXTURES.contains(hash)
				|| ConfigManager.get().essenceTextures.contains(hash);
	}

	/**
	 * Delivers verdicts on clicked heads: gone means it was an essence skull,
	 * so the texture is saved and announced once; still standing past the
	 * deadline means scenery, forgotten without a word.
	 */
	private static void judgePendingHeads(Minecraft client) {
		if (pendingHeads.isEmpty()) {
			return;
		}

		for (var iterator = pendingHeads.iterator(); iterator.hasNext(); ) {
			PendingHead pending = iterator.next();

			if (client.level.getBlockState(pending.pos()).isAir()) {
				iterator.remove();

				// The vanished head also retires any essence marker on it.
				for (Marker marker : markers()) {
					if (marker.kind == Kind.ESSENCE
							&& marker.pos.distSqr(pending.pos()) <= 2 * 2) {
						marker.done = true;
					}
				}

				if (!isEssence(pending.hash())) {
					ConfigManager.get().essenceTextures.add(pending.hash());
					ConfigManager.save();

					var chat = client.gui.hud.getChat();
					chat.addClientSystemMessage(
							net.minecraft.network.chat.Component.empty());
					chat.addClientSystemMessage(
							net.minecraft.network.chat.Component.literal(
											"SkyAid learned an essence skull - "
													+ "they will be marked from now on.")
									.withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
					chat.addClientSystemMessage(
							net.minecraft.network.chat.Component.empty());
				}
			} else if (tickClock >= pending.deadline()) {
				iterator.remove();
			}
		}
	}

	private static boolean chestNear(Minecraft client, BlockPos pos) {
		for (BlockPos probe : BlockPos.betweenClosed(
				pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
			Block block = client.level.getBlockState(probe).getBlock();

			if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
				return true;
			}
		}

		return false;
	}

	private static boolean headNear(Minecraft client, BlockPos pos) {
		for (BlockPos probe : BlockPos.betweenClosed(
				pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
			Block block = client.level.getBlockState(probe).getBlock();

			if (block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD) {
				return true;
			}
		}

		return false;
	}

	/**
	 * The skin-texture hash of the player head at {@code pos} - the last path
	 * segment of its textures.minecraft.net URL, which is the head's identity.
	 * Also read by /skyaid dump, so a new secret texture can be captured in
	 * game.
	 */
	public static Optional<String> skinHash(
			net.minecraft.world.level.Level level, BlockPos pos) {
		if (!(level.getBlockEntity(pos)
				instanceof net.minecraft.world.level.block.entity.SkullBlockEntity skull)
				|| skull.getOwnerProfile() == null) {
			return Optional.empty();
		}

		for (var property : skull.getOwnerProfile().partialProfile()
				.properties().get("textures")) {
			try {
				String json = new String(
						java.util.Base64.getDecoder().decode(property.value()),
						java.nio.charset.StandardCharsets.UTF_8);
				int marker = json.lastIndexOf("texture/");

				if (marker < 0) {
					continue;
				}

				int end = json.indexOf('"', marker);

				if (end > marker) {
					return Optional.of(json.substring(
							marker + "texture/".length(), end));
				}
			} catch (IllegalArgumentException e) {
				// Not base64 - not a texture we can identify.
			}
		}

		return Optional.empty();
	}

	/** Marker states for /skyaid dump. */
	public static void dumpInto(StringBuilder out) {
		out.append("  markers:   ").append(byRoom.size()).append(" room(s) held, ")
				.append(fallback.size()).append(" fallback\n");

		List<Marker> current = currentRoomMarkers();

		for (Marker marker : current.isEmpty() ? fallback : current) {
			out.append("    ").append(marker.done ? "x " : "- ")
					.append(marker.kind.label).append("  ")
					.append(marker.pos.getX()).append(' ')
					.append(marker.pos.getY()).append(' ')
					.append(marker.pos.getZ()).append('\n');
		}
	}
}
