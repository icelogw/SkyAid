package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * SkyAid's touches on Hypixel's REAL SkyBlock Menu: three warp items in the
 * empty fourth row, and the user's rearrangement - Quests and Calendar
 * moved up beside Stats in row one. A moved item is the REAL stack drawn at
 * its new slot (live icon, live tooltip via setTooltipForNextFrame); its
 * click forwards to the original slot, so the server sees exactly the click
 * it expects. The vacated slot is covered with the menu's own filler look.
 *
 * <p>One user click, one action, always. Everything untouched stays native.
 */
public final class SkyMenu {
	private record Extra(int slot, net.minecraft.world.item.Item icon,
			String head, String name, List<String> lore, String command) {
	}

	/** A real menu item shown at a new slot; clicks forward to the old. */
	private record Move(int from, int to) {
	}

	/** A real item wearing a different icon - tooltip and click stay native. */
	private record Reskin(int slot, net.minecraft.world.item.Item icon) {
	}

	/** Row-4 centre of the real menu (dump-verified pane filler there). */
	private static final List<Extra> EXTRAS = List.of(
			// Head skins captured from the real Fast Travel menu 2026-08-26 -
			// each warp wears its island's own face.
			new Extra(39, Items.WHEAT,
					"ewogICJ0aW1lc3RhbXAiIDogMTYyMTY5NjIwMzk5MCwKICAicHJvZmlsZUlkIiA6ICI3NzI3ZDM1NjY5Zjk0MTUxODAyM2Q2MmM2ODE3NTkxOCIsCiAgInByb2ZpbGVOYW1lIiA6ICJsaWJyYXJ5ZnJlYWsiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGE0ZmYxN2U4NDU4M2I0YTYyMDA1YTNlODFmYmMyMmY2YWVlNTk0ZWZlNTI5NGIwNmYzZWVmZmNkZjNhZjI4MiIKICAgIH0KICB9Cn0=",
					"Warp: Garden",
					List.of("To your Garden.", "", "Click to warp!"),
					"warp garden"),
			new Extra(40, Items.OAK_DOOR,
					"ewogICJ0aW1lc3RhbXAiIDogMTY1NzE0NDA2NTAzNywKICAicHJvZmlsZUlkIiA6ICJkZjY2OWIyOGFmNWE0MTNjODFhNjcwOGQ0ZDIyM2FlNSIsCiAgInByb2ZpbGVOYW1lIiA6ICJsYW5nX3FpbmciLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzVmNGI0MGNlZjllMDE3Y2Q0MTEyZDI2YjYyNTU3ZjhjMWQ1YjE4OWRhMmU5OTUzNDIyMmJjOGNlYzdkOTE5NiIKICAgIH0KICB9Cn0=",
					"Warp: Home",
					List.of("To your island.", "", "Click to warp!"),
					"warp home"),
			new Extra(41, Items.GRASS_BLOCK,
					"eyJ0aW1lc3RhbXAiOjE1NDYwMzY3MzY3MjMsInByb2ZpbGVJZCI6ImE5MGI4MmIwNzE4NTQ0ZjU5YmE1MTZkMGY2Nzk2NDkwIiwicHJvZmlsZU5hbWUiOiJJbUZhdFRCSCIsInNpZ25hdHVyZVJlcXVpcmVkIjp0cnVlLCJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDdjYzY2ODc0MjNkMDU3MGQ1NTZhYzUzZTA2NzZjYjU2M2JiZGQ5NzE3Y2Q4MjY5YmRlYmVkNmY2ZDRlN2JmOCIsIm1ldGFkYXRhIjp7Im1vZGVsIjoic2xpbSJ9fX19",
					"Warp: Hub",
					List.of("To the hub.", "", "Click to warp!"),
					"hub"),
			// The market pair, far-right column reading down like a list
			//. Their commands are Booster Cookie perks -
			// Hypixel says so itself when one is missing.
			new Extra(17, Items.GOLD_INGOT,
					// Hypixel's own Bazaar head, captured from the Booster
					// Cookie menu.
					"eyJ0aW1lc3RhbXAiOjE1NzMyMjM2NDc4NDcsInByb2ZpbGVJZCI6IjQxZDNhYmMyZDc0OTQwMGM5MDkwZDU0MzRkMDM4MzFiIiwicHJvZmlsZU5hbWUiOiJNZWdha2xvb24iLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2MyMzJlMzgyMDg5NzQyOTE1NzYxOWIwZWUwOTlmZWMwNjI4ZjYwMmZmZjEyYjY5NWRlNTRhZWYxMWQ5MjNhZDcifX19",
					"Bazaar",
					List.of("Opens the bazaar.", "",
							"Needs a Booster Cookie.", "", "Click to open!"),
					"bz"),
			// Hypixel's own AH icon is not a head at all (verified by dump:
			// no head on "Auctions Browser") - it is the classic golden
			// horse armor "gavel".
			new Extra(26, Items.GOLDEN_HORSE_ARMOR, null, "Auction House",
					List.of("Opens the auction house.", "",
							"Needs a Booster Cookie.", "", "Click to open!"),
					"ah"));

	/**
	 * The user's layout: Quests to row1 col2, Calendar to row1 col6, and
	 * row two sits two columns right, Storage ending under Calendar.
	 */
	private static final List<Move> MOVES = List.of(
			new Move(23, 11),
			new Move(24, 15),
			new Move(19, 20),
			new Move(20, 21),
			new Move(21, 23),   // Recipes and Leveling swapped - Leveling
			                     // ends up at its native slot 22 and stays put
			new Move(25, 24));   // row 2 sits two right; Storage under Calendar

	/** Fast Travel wears a beacon. */
	private static final List<Reskin> RESKINS = List.of(
			new Reskin(47, Items.BEACON));

	/** A 6-row generic container's centred dimensions. */
	private static final int CONTAINER_WIDTH = 176;
	private static final int CONTAINER_HEIGHT = 222;

	/**
	 * Whether a slot's vanilla tooltip is muted: the vacated originals of
	 * every move, on the SkyBlock Menu only. Called by the container
	 * screen mixin for every hovered slot.
	 */
	public static boolean tooltipMuted(AbstractContainerScreen<?> screen, int slot) {
		if (!ConfigManager.get().enabled || !ConfigManager.get().menuReplace
				|| !"SkyBlock Menu".equals(screen.getTitle().getString())) {
			return false;
		}

		for (Move move : MOVES) {
			if (move.from() == slot) {
				return true;
			}
		}

		return false;
	}

	private SkyMenu() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> container)
					|| !ConfigManager.get().enabled
					|| !ConfigManager.get().menuReplace
					|| !HypixelDetector.isOnHypixel()
					|| !"SkyBlock Menu".equals(container.getTitle().getString())) {
				return;
			}

			ScreenEvents.afterForeground(screen).register(
					(s, extractor, mouseX, mouseY, delta) ->
							draw(container, extractor, mouseX, mouseY));

			// Clicks on OUR slots never reach the menu underneath.
			ScreenMouseEvents.allowMouseClick(screen).register(
					(s, mouse) -> !click(container, (int) mouse.x(), (int) mouse.y()));
		});
	}

	private static int slotX(Screen screen, int slot) {
		return (screen.width - CONTAINER_WIDTH) / 2 + 8 + (slot % 9) * 18;
	}

	private static int slotY(Screen screen, int slot) {
		return (screen.height - CONTAINER_HEIGHT) / 2 + 18 + (slot / 9) * 18;
	}

	private static boolean over(int mouseX, int mouseY, int x, int y) {
		return mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
	}

	/** Built heads, cached per slot - NEVER in a static initializer. */
	private static final java.util.Map<Integer, ItemStack> HEAD_CACHE =
			new java.util.HashMap<>();

	/**
	 * A warp's icon: its island's real head, built from the captured skin
	 * (the museum recipe - profile component with the texture property);
	 * the vanilla item stands in only if the head fails to build.
	 */
	private static ItemStack iconFor(Extra extra) {
		if (extra.head() == null) {
			return new ItemStack(extra.icon());
		}

		return HEAD_CACHE.computeIfAbsent(extra.slot(), slot -> {
			try {
				ItemStack head = new ItemStack(Items.PLAYER_HEAD);
				com.google.common.collect.Multimap<String,
						com.mojang.authlib.properties.Property> textures =
						com.google.common.collect.LinkedHashMultimap.create();
				textures.put("textures", new com.mojang.authlib.properties
						.Property("textures", extra.head()));
				var profile = new com.mojang.authlib.GameProfile(
						java.util.UUID.nameUUIDFromBytes(extra.name().getBytes(
								java.nio.charset.StandardCharsets.UTF_8)),
						"skyaid",
						new com.mojang.authlib.properties.PropertyMap(textures));
				head.set(net.minecraft.core.component.DataComponents.PROFILE,
						net.minecraft.world.item.component.ResolvableProfile
								.createResolved(profile));
				return head;
			} catch (Exception e) {
				return new ItemStack(extra.icon());
			}
		});
	}

	private static boolean isDestination(int slot) {
		for (Move move : MOVES) {
			if (move.to() == slot) {
				return true;
			}
		}

		return false;
	}

	private static ItemStack realStack(AbstractContainerScreen<?> screen, int slot) {
		var slots = screen.getMenu().slots;
		return slot < slots.size() ? slots.get(slot).getItem() : ItemStack.EMPTY;
	}

	private static void draw(AbstractContainerScreen<?> screen,
			GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		var font = Minecraft.getInstance().font;
		Extra hovered = null;

		for (Extra extra : EXTRAS) {
			int x = slotX(screen, extra.slot());
			int y = slotY(screen, extra.slot());

			// Repaint the slot over Hypixel's filler pane first, so the pane
			// cannot peek through our sprite's transparent pixels.
			extractor.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);

			extractor.item(iconFor(extra), x, y);

			if (over(mouseX, mouseY, x, y)) {
				hovered = extra;
				extractor.fill(x, y, x + 16, y + 16, 0x80FFFFFF);
			}
		}

		// Pass one: erase every moved item with a body-only fill (the
		// vanilla bevel stays vanilla's own pixels), then re-fill the
		// truly vacated slots with HYPIXEL'S OWN filler stack, borrowed
		// live from slot 0 - identical by definition, whatever pane they
		// use. Slots another item slides into stay pane-free.
		ItemStack filler = realStack(screen, 0);

		if (filler.isEmpty()) {
			filler = new ItemStack(Items.STAINED_GLASS_PANE.black());
		}

		for (Move move : MOVES) {
			int fx = slotX(screen, move.from());
			int fy = slotY(screen, move.from());
			extractor.fill(fx, fy, fx + 16, fy + 16, 0xFF8B8B8B);

			if (!isDestination(move.from())) {
				extractor.item(filler, fx, fy);
			}
		}

		// Pass two: the REAL stacks at their new homes - live icon, live
		// tooltip. Separate pass so a slot can be vacated AND refilled
		// (Storage slides into the Quests spot).
		for (Move move : MOVES) {
			ItemStack real = realStack(screen, move.from());

			if (real.isEmpty()) {
				continue;
			}

			int tx = slotX(screen, move.to());
			int ty = slotY(screen, move.to());

			// Destinations can natively hold a filler pane (row 1 does) -
			// cover it, or it peeks through the moved sprite's transparent
			// pixels.
			extractor.fill(tx, ty, tx + 16, ty + 16, 0xFF8B8B8B);
			extractor.item(real, tx, ty);

			if (over(mouseX, mouseY, tx, ty)) {
				extractor.fill(tx, ty, tx + 16, ty + 16, 0x80FFFFFF);
				extractor.setTooltipForNextFrame(font, real, mouseX, mouseY);
			}
		}

		for (Reskin reskin : RESKINS) {
			int x = slotX(screen, reskin.slot());
			int y = slotY(screen, reskin.slot());
			extractor.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
			extractor.item(new ItemStack(reskin.icon()), x, y);

			// Our opaque cover also hides vanilla's hover glow; redraw it.
			if (over(mouseX, mouseY, x, y)) {
				extractor.fill(x, y, x + 16, y + 16, 0x80FFFFFF);
			}
		}

		if (hovered != null) {
			List<String> lines = new ArrayList<>();
			lines.add(hovered.name());
			lines.addAll(hovered.lore());
			MuseumTracker.drawVanillaTooltip(extractor, font, mouseX, mouseY, lines);
		}
	}

	/** One user click, one action - command or forwarded container click. */
	private static boolean click(AbstractContainerScreen<?> screen,
			int mouseX, int mouseY) {
		for (Extra extra : EXTRAS) {
			if (over(mouseX, mouseY, slotX(screen, extra.slot()),
					slotY(screen, extra.slot()))) {
				var client = Minecraft.getInstance();
				client.setScreenAndShow(null);
				var connection = client.getConnection();

				if (connection != null) {
					connection.sendCommand(extra.command());
				}

				return true;
			}
		}

		// New homes first, then inert vacated slots - a slot can be both
		// (Storage now lives where Quests was), and the live item wins.
		for (Move move : MOVES) {
			if (over(mouseX, mouseY, slotX(screen, move.to()),
					slotY(screen, move.to()))) {
				var client = Minecraft.getInstance();

				if (client.gameMode != null && client.player != null) {
					client.gameMode.handleContainerInput(
							screen.getMenu().containerId, move.from(), 0,
							ContainerInput.PICKUP, client.player);
				}

				return true;
			}
		}

		for (Move move : MOVES) {
			if (over(mouseX, mouseY, slotX(screen, move.from()),
					slotY(screen, move.from()))) {
				return true;
			}
		}

		return false;
	}
}
