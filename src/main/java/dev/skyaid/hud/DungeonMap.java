package dev.skyaid.hud;

import dev.skyaid.SkyAidClient;
import dev.skyaid.config.Config;
import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.dungeon.core.SecretsBoard;
import dev.skyaid.parse.RoomMath;
import dev.skyaid.parse.SkyblockState;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Draws Hypixel's dungeon map in a corner of the screen while in the Catacombs,
 * with each identified room's secret count ("2/5") written on its map tile.
 *
 * <p>Deliberately its own overlay rather than a line of the arrangeable readout:
 * a map is a block, not a row, and it belongs pinned to a corner whatever the
 * text layout is doing.
 *
 * <p>Hypixel already gives every dungeon player the "Magical Map" in the last
 * hotbar slot; this renders that exact map item's pixels - data the client
 * already has - without having to hold it. Renders nothing outside the
 * Catacombs, and nothing when the slot holds no map.
 *
 * <p>The counts need to know where a WORLD position lands on the MAP. That
 * transform is not published anywhere, so it is fitted from the one
 * correspondence the map hands over every frame: the player's own marker.
 * One walk of a few blocks pins the scale (pixels per block); after that a
 * single marker sample per frame keeps the offset exact.
 */
public final class DungeonMap {
	/** Vanilla maps are 128x128; the scale setting multiplies this. */
	private static final int MAP_SIZE = 128;

	/** The Magical Map lives in the ninth hotbar slot. */
	private static final int MAP_SLOT = 8;

	/** How far the player must move before the scale fit is trusted. */
	private static final double FIT_MIN_BLOCKS = 16;

	/** Hypixel's dungeon maps run about half a pixel per block; far outside that is junk. */
	private static final double SCALE_MIN = 0.2;
	private static final double SCALE_MAX = 2.5;

	/** World blocks per room cell. */
	private static final int ROOM_BLOCKS = 32;

	/** Fitted pixels-per-block, or -1 while unknown. */
	private static double mapScale = -1;
	private static double offsetX;
	private static double offsetZ;

	/** The fit baseline: where player and marker were when it started. */
	private static boolean haveBaseline;
	private static double baseWorldX;
	private static double baseWorldZ;
	private static double basePixelX;
	private static double basePixelZ;

	private DungeonMap() {
	}

	public static void register() {
		Identifier id = Identifier.fromNamespaceAndPath(SkyAidClient.MOD_ID, "dungeon_map");

		HudElementRegistry.addLast(id, (extractor, deltaTracker) -> {
			Config config = ConfigManager.get();

			if (!config.enabled || !config.dungeonMap.visible) {
				return;
			}

			if (!HypixelDetector.isOnHypixel()) {
				resetMapping();
				return;
			}

			SkyblockState state = SkyblockTracker.state();

			if (!state.inSkyblock() || !state.inCatacombs()) {
				resetMapping();
				return;
			}

			Minecraft client = Minecraft.getInstance();

			if (client.player == null || client.level == null) {
				return;
			}

			ItemStack stack = client.player.getInventory().getItem(MAP_SLOT);
			MapId mapId = stack.get(DataComponents.MAP_ID);

			if (mapId == null) {
				return;
			}

			MapItemSavedData data = client.level.getMapData(mapId);

			if (data == null) {
				return;
			}

			updateMapping(data, client);

			// A fresh state each frame: extraction appends decorations, and a
			// reused one would pile up a marker per frame.
			MapRenderState renderState = new MapRenderState();
			client.getMapRenderer().extractRenderState(mapId, data, renderState);

			int[] box = measure(config.dungeonMap, extractor.guiWidth(), extractor.guiHeight());

			var pose = extractor.pose();
			pose.pushMatrix();
			pose.translate(box[0], box[1]);
			pose.scale(config.dungeonMap.scale, config.dungeonMap.scale);
			extractor.map(renderState);
			drawRoomCounts(extractor, client);
			pose.popMatrix();
		});
	}

	/**
	 * Keeps the world-to-map fit current from the player's own marker. On
	 * Hypixel the self marker is the green FRAME pointer (teammates draw as
	 * other types); a plain PLAYER arrow is accepted as fallback so the debug
	 * world works too.
	 */
	private static void updateMapping(MapItemSavedData data, Minecraft client) {
		MapDecoration self = null;

		for (MapDecoration decoration : data.getDecorations()) {
			if (decoration.type().equals(MapDecorationTypes.FRAME)) {
				self = decoration;
				break;
			}

			if (self == null && decoration.type().equals(MapDecorationTypes.PLAYER)) {
				self = decoration;
			}
		}

		if (self == null) {
			return;
		}

		// Decoration coordinates are half-pixels from the map centre.
		double pixelX = self.x() / 2.0 + 64;
		double pixelZ = self.y() / 2.0 + 64;
		double worldX = client.player.getX();
		double worldZ = client.player.getZ();

		if (mapScale <= 0) {
			if (!haveBaseline) {
				haveBaseline = true;
				baseWorldX = worldX;
				baseWorldZ = worldZ;
				basePixelX = pixelX;
				basePixelZ = pixelZ;
				return;
			}

			double movedX = Math.abs(worldX - baseWorldX);
			double movedZ = Math.abs(worldZ - baseWorldZ);

			if (movedX + movedZ < FIT_MIN_BLOCKS) {
				return;
			}

			// The dungeon map is north-up and uniformly scaled, so one axis
			// pair is enough; both together average out marker rounding.
			double fitted = (Math.abs(pixelX - basePixelX)
					+ Math.abs(pixelZ - basePixelZ)) / (movedX + movedZ);

			if (fitted < SCALE_MIN || fitted > SCALE_MAX) {
				// A clamped marker (map edge) or a teleport: re-baseline.
				baseWorldX = worldX;
				baseWorldZ = worldZ;
				basePixelX = pixelX;
				basePixelZ = pixelZ;
				return;
			}

			mapScale = fitted;
		}

		offsetX = pixelX - worldX * mapScale;
		offsetZ = pixelZ - worldZ * mapScale;
	}

	private static void resetMapping() {
		mapScale = -1;
		haveBaseline = false;
	}

	/**
	 * "2/5" on each identified room's map tile - green when the room is
	 * finished, gold when started, white untouched. Rooms the run has not
	 * identified yet show nothing rather than a guess.
	 */
	private static void drawRoomCounts(GuiGraphicsExtractor extractor, Minecraft client) {
		if (mapScale <= 0) {
			return;
		}

		var font = client.font;

		for (SecretsBoard.RoomSummary summary : SecretsBoard.summaries()) {
			if (summary.total() == 0) {
				continue;
			}

			double worldX = 0;
			double worldZ = 0;

			for (RoomMath.Cell cell : summary.cells()) {
				worldX += cell.x() + ROOM_BLOCKS / 2.0;
				worldZ += cell.z() + ROOM_BLOCKS / 2.0;
			}

			worldX /= summary.cells().size();
			worldZ /= summary.cells().size();

			float pixelX = (float) (worldX * mapScale + offsetX);
			float pixelZ = (float) (worldZ * mapScale + offsetZ);

			if (pixelX < 0 || pixelX > MAP_SIZE || pixelZ < 0 || pixelZ > MAP_SIZE) {
				continue;
			}

			String label = summary.done() + "/" + summary.total();
			int color = summary.done() >= summary.total() ? 0xFF54FC54
					: summary.done() > 0 ? 0xFFFFD24D : 0xFFFFFFFF;

			var pose = extractor.pose();
			pose.pushMatrix();
			pose.translate(pixelX, pixelZ);

			// Rooms are ~20 map pixels; full-size text would cover the tile.
			pose.scale(0.7f, 0.7f);
			extractor.text(font, label, -font.width(label) / 2, -4, color, true);
			pose.popMatrix();
		}
	}

	/**
	 * The map's screen rectangle as {x, y, size, size}, shared with the position
	 * screen so the drag target is exactly the drawn box. Same anchoring rule as
	 * the main HUD: a fraction past the middle measures from the far edge.
	 */
	static int[] measure(Config.HudSettings settings, int guiWidth, int guiHeight) {
		int size = Math.round(MAP_SIZE * settings.scale);

		return new int[]{
				anchored(settings.x, guiWidth, size),
				anchored(settings.y, guiHeight, size),
				size, size};
	}

	private static int anchored(float fraction, int extent, int size) {
		int position = Math.round(extent * fraction);
		return fraction > 0.5f ? position - size : position;
	}
}
