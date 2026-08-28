package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.dungeon.core.MarkerRenderer;
import dev.skyaid.parse.SkyblockState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Glowing boxes on Garden pests, so the one bug hiding in a sea of crops is
 * findable across the plots. Pests are found by the names Hypixel floats over
 * them; the box and label reuse the dungeon marker pipeline (through-wall,
 * fades when the pest dies or despawns).
 *
 * <p>The pest names are ecosystem knowledge, UNVERIFIED against live
 * captures - the dump's GARDEN section lists every named entity nearby, which
 * is how a wrong or missing name gets corrected.
 *
 * <p>Display only: nothing is targeted, hit, or pathed for the player.
 */
public final class PestHighlight {
	/** Pest green - bright against both crops and night. */
	private static final int COLOR = 0x7CFC00;

	/** How often the entity list is rescanned; drawing happens every tick. */
	private static final int SCAN_INTERVAL_TICKS = 10;

	/**
	 * Far enough to cover the whole Garden - the real cap is how far the
	 * server syncs entities, so this is deliberately generous.
	 */
	private static final double RANGE = 200;

	private static final int MAX_MARKED = 12;

	/** The pest species names Hypixel floats over them (unverified). */
	private static final String[] PESTS = {
			"Beetle", "Cricket", "Earthworm", "Fly", "Locust",
			"Mite", "Mosquito", "Moth", "Rat", "Slug"};

	private record Marked(int entityId, BlockPos pos, String label) {
	}

	/**
	 * A pest that jumped this far between scans has moved to a new spot -
	 * ordinary crawling never covers this in one scan interval. Vertical
	 * movement counts from much less: an Earthworm burrows straight DOWN
	 * after losing HP (the case this exists for), only a few blocks deep.
	 */
	private static final double RELOCATE_DISTANCE_SQ = 10 * 10;
	private static final int RELOCATE_VERTICAL = 3;

	/**
	 * How long the guide line to a relocated pest stays up - long enough
	 * to WALK there, since crossing plots is the whole point of it.
	 */
	private static final long LINE_MILLIS = 15_000;

	/**
	 * How long a vanished pest is remembered: one that reappears within
	 * this window under a NEW entity id (burrow, despawn-respawn) still
	 * counts as the same bug having moved.
	 */
	private static final long REAPPEAR_MILLIS = 10_000;

	private record Vanished(String label, BlockPos pos, long at) {
	}

	/**
	 * A relocation arrow: the fixed "it went from here" end and the last
	 * KNOWN position of the pest. Both ends are world positions, so the
	 * arrow survives the entity unloading when either of you walks far -
	 * which is exactly when it is being followed.
	 */
	private record Trail(BlockPos from, BlockPos to, long until) {
	}

	private static int tickCounter;
	private static List<Marked> marked = List.of();

	/** Last scan's marker per pest entity, to notice relocations. */
	private static final java.util.Map<Integer, Marked> lastSeen =
			new java.util.HashMap<>();

	/** Pests that disappeared recently - burrowed or despawned. */
	private static final List<Vanished> vanished = new ArrayList<>();

	/** Pest entity id -> its relocation trail while one is showing. */
	private static final java.util.Map<Integer, Trail> trails =
			new java.util.HashMap<>();

	private PestHighlight() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ConfigManager.get().enabled || !ConfigManager.get().pestHighlight) {
				clearTracking();
				return;
			}

			if (client.level == null || client.player == null
					|| !HypixelDetector.isOnHypixel()) {
				clearTracking();
				return;
			}

			SkyblockState state = SkyblockTracker.state();

			if (!state.inSkyblock() || !onGarden(state)) {
				clearTracking();
				return;
			}

			if (++tickCounter >= SCAN_INTERVAL_TICKS) {
				tickCounter = 0;
				rescan(client);
			}

			// Redrawn every tick from the cached scan, like the solvers -
			// the 120ms gizmo lifetime otherwise reads as flicker.
			long now = System.currentTimeMillis();

			for (Marked pest : marked) {
				MarkerRenderer.point(pest.pos(), pest.label(), COLOR);
			}

			// The relocation arrows: a faint box at the OLD spot, a line to
			// the pest's last known position, and - when the pest itself is
			// out of sync range - a faint box at the destination too, so
			// there is something to walk toward.
			for (Trail trail : trails.values()) {
				if (trail.until() <= now) {
					continue;
				}

				MarkerRenderer.boxDim(trail.from(), COLOR);

				if (!markedAt(trail.to())) {
					MarkerRenderer.boxDim(trail.to(), COLOR);
				}

				net.minecraft.gizmos.Gizmos.line(
								net.minecraft.world.phys.Vec3.atCenterOf(trail.from()),
								net.minecraft.world.phys.Vec3.atCenterOf(trail.to()),
								0xFF000000 | COLOR, 2.0f)
						.persistForMillis(120)
						.setAlwaysOnTop();
			}
		});
	}

	/**
	 * Off, away, or out of the Garden: markers AND the relocation memory
	 * go - stale ids surviving a warp read fresh spawns as "reappearing".
	 */
	private static void clearTracking() {
		marked = List.of();
		lastSeen.clear();
		vanished.clear();
		trails.clear();
	}

	/**
	 * The Garden's zone reads "The Garden" on the barn plot and "Plot - N"
	 * on the field plots (assumption until a dump confirms); debug mode
	 * lifts the gate for offline testing, as everywhere.
	 */
	private static boolean onGarden(SkyblockState state) {
		return ConfigManager.get().debug || state.location()
				.map(zone -> zone.contains("Garden") || zone.startsWith("Plot"))
				.orElse(false);
	}

	private static void rescan(Minecraft client) {
		List<Marked> found = new ArrayList<>();

		for (Entity entity : client.level.entitiesForRendering()) {
			if (found.size() >= MAX_MARKED) {
				break;
			}

			if (entity.distanceTo(client.player) > RANGE) {
				continue;
			}

			var name = entity.getCustomName();

			if (name == null) {
				continue;
			}

			String text = name.getString();

			for (String pest : PESTS) {
				if (text.contains(pest)) {
					// Name-tag armour stands float above the pest itself;
					// one block down centres the box on the bug.
					found.add(new Marked(entity.getId(),
							entity.blockPosition().below(), pest));
					break;
				}
			}
		}

		// Relocation check: a jump since the last scan - or a brand-new id
		// of a species that just vanished (the Earthworm resurfacing) -
		// starts (or restarts) that pest's guide line.
		long now = System.currentTimeMillis();
		var seen = new java.util.HashSet<Integer>();

		for (Marked pest : found) {
			seen.add(pest.entityId());
			Marked previous = lastSeen.put(pest.entityId(), pest);

			if (previous == null) {
				BlockPos origin = reappearedFrom(pest.label(), now);

				if (origin != null) {
					trails.put(pest.entityId(),
							new Trail(origin, pest.pos(), now + LINE_MILLIS));
				}
			} else if (relocated(previous.pos(), pest.pos())) {
				trails.put(pest.entityId(), new Trail(previous.pos(),
						pest.pos(), now + LINE_MILLIS));
			} else {
				// The pest keeps crawling after the jump: the arrow's far
				// end follows it while it stays in sight.
				trails.computeIfPresent(pest.entityId(), (id, trail) ->
						new Trail(trail.from(), pest.pos(), trail.until()));
			}
		}

		// Ids gone this scan are remembered, with WHERE they were, for the
		// reappear window. Their trails deliberately survive - an unloaded
		// entity is not a finished journey; the timer ends the arrow.
		for (var entry : lastSeen.entrySet()) {
			if (!seen.contains(entry.getKey())) {
				vanished.add(new Vanished(entry.getValue().label(),
						entry.getValue().pos(), now));
			}
		}

		lastSeen.keySet().retainAll(seen);
		trails.values().removeIf(trail -> trail.until() <= now);

		marked = found;
	}

	/** Whether a live pest marker already stands at this position. */
	private static boolean markedAt(BlockPos pos) {
		for (Marked pest : marked) {
			if (pest.pos().equals(pos)) {
				return true;
			}
		}

		return false;
	}

	private static boolean relocated(BlockPos previous, BlockPos current) {
		return previous.distSqr(current) > RELOCATE_DISTANCE_SQ
				|| Math.abs(previous.getY() - current.getY()) >= RELOCATE_VERTICAL;
	}

	/**
	 * Where a pest of this species vanished within the reappear window, or
	 * null; the match is consumed so one burrow explains one resurfacing.
	 */
	private static BlockPos reappearedFrom(String label, long now) {
		var iterator = vanished.iterator();
		BlockPos origin = null;

		while (iterator.hasNext()) {
			Vanished gone = iterator.next();

			if (now - gone.at() > REAPPEAR_MILLIS) {
				iterator.remove();
			} else if (origin == null && gone.label().equals(label)) {
				iterator.remove();
				origin = gone.pos();
			}
		}

		return origin;
	}

	/**
	 * Every named entity nearby, invisible characters spelled out - the
	 * capture that corrects a wrong pest name without code reading.
	 */
	public static void dumpInto(StringBuilder out) {
		out.append("\nGARDEN / NAMED ENTITIES:\n");
		var client = Minecraft.getInstance();

		if (client.level == null || client.player == null) {
			out.append("  (not in a world)\n");
			return;
		}

		out.append("  highlighting: ").append(marked.size()).append(" pest(s)\n");
		int listed = 0;

		for (Entity entity : client.level.entitiesForRendering()) {
			var name = entity.getCustomName();

			if (name == null || entity.distanceTo(client.player) > 40) {
				continue;
			}

			if (++listed > 40) {
				out.append("  ... (more cut)\n");
				break;
			}

			out.append("  ").append(entity.getClass().getSimpleName())
					.append(" @ ").append(entity.blockPosition().toShortString())
					.append("  \"").append(spellOut(name.getString())).append("\"\n");
		}

		if (listed == 0) {
			out.append("  (no named entities within 40 blocks)\n");
		}
	}

	/** Non-printable and non-ASCII characters as {@code <U+XXXX>}. */
	private static String spellOut(String text) {
		StringBuilder out = new StringBuilder(text.length());

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);

			if (c >= 0x20 && c < 0x7F) {
				out.append(c);
			} else {
				out.append(String.format("<U+%04X>", (int) c));
			}
		}

		return out.toString();
	}
}
