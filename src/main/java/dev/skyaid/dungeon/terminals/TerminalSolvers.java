package dev.skyaid.dungeon.terminals;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.SkyblockTracker;
import dev.skyaid.parse.ItemNames;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F7/M7 terminal overlays: each terminal GUI gets its correct clicks marked
 * with a green wash (and the rubix terminal a click count per pane). Display
 * only - highlighting is all it does, every click stays the player's.
 *
 * <p>The titles are ecosystem knowledge, UNVERIFIED against captures: a
 * wording miss means no overlay appears and the terminal plays vanilla. The
 * dump's TERMINAL section prints the open container's exact title and grid,
 * which is the capture that corrects this file.
 */
public final class TerminalSolvers {
	private static final int HIGHLIGHT = 0x8055FF55;
	private static final int HIGHLIGHT_SOFT = 0x4055FF55;

	private static final Pattern STARTS_WITH =
			Pattern.compile("What starts with: .?'(\\w)'.?");
	private static final Pattern SELECT_COLOR =
			Pattern.compile("Select all the ([A-Z ]+) items!");

	/** The rubix terminal's colour cycle, in click order. */
	private static final String[] RUBIX_ORDER =
			{"red", "orange", "yellow", "green", "blue"};

	/** The last terminal-ish title seen, for the dump. */
	private static volatile String lastTitle = "(none)";

	private TerminalSolvers() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> container)
					|| !ConfigManager.get().enabled
					|| !ConfigManager.get().terminalOverlays
					|| !SkyblockTracker.state().inCatacombs()) {
				return;
			}

			String title = screen.getTitle().getString();

			if (!isTerminal(title)) {
				return;
			}

			lastTitle = title;
			ScreenEvents.afterForeground(screen).register(
					(s, extractor, mouseX, mouseY, delta) ->
							draw(container, title, extractor));
		});
	}

	private static boolean isTerminal(String title) {
		return title.equals("Correct all the panes!")
				|| title.equals("Click in order!")
				|| title.equals("Change all to same color!")
				|| STARTS_WITH.matcher(title).matches()
				|| SELECT_COLOR.matcher(title).matches();
	}

	private static void draw(AbstractContainerScreen<?> screen, String title,
			GuiGraphicsExtractor extractor) {
		Matcher startsWith = STARTS_WITH.matcher(title);
		Matcher color = SELECT_COLOR.matcher(title);

		if (title.equals("Correct all the panes!")) {
			drawPanes(screen, extractor);
		} else if (title.equals("Click in order!")) {
			drawOrder(screen, extractor);
		} else if (title.equals("Change all to same color!")) {
			drawRubix(screen, extractor);
		} else if (startsWith.matches()) {
			drawNameMatch(screen, extractor, startsWith.group(1));
		} else if (color.matches()) {
			drawColorMatch(screen, extractor, color.group(1));
		}
	}

	/** Every red pane wants a click. */
	private static void drawPanes(AbstractContainerScreen<?> screen,
			GuiGraphicsExtractor extractor) {
		forEachTerminalSlot(screen, (slot, stack) -> {
			if (idPath(stack).equals("red_stained_glass_pane")) {
				wash(extractor, screen, slot, HIGHLIGHT);
			}
		});
	}

	/** The lowest-numbered red pane is next; the runner-up glows softer. */
	private static void drawOrder(AbstractContainerScreen<?> screen,
			GuiGraphicsExtractor extractor) {
		Slot next = null;
		Slot after = null;
		int nextCount = Integer.MAX_VALUE;
		int afterCount = Integer.MAX_VALUE;

		for (Slot slot : screen.getMenu().slots) {
			ItemStack stack = slot.getItem();

			if (!isTerminalSlot(screen, slot)
					|| !idPath(stack).equals("red_stained_glass_pane")) {
				continue;
			}

			if (stack.getCount() < nextCount) {
				after = next;
				afterCount = nextCount;
				next = slot;
				nextCount = stack.getCount();
			} else if (stack.getCount() < afterCount) {
				after = slot;
				afterCount = stack.getCount();
			}
		}

		if (next != null) {
			wash(extractor, screen, next, HIGHLIGHT);
		}

		if (after != null) {
			wash(extractor, screen, after, HIGHLIGHT_SOFT);
		}
	}

	/** Un-glinted items whose name starts with the letter want clicks. */
	private static void drawNameMatch(AbstractContainerScreen<?> screen,
			GuiGraphicsExtractor extractor, String letter) {
		String prefix = letter.toLowerCase(Locale.ROOT);

		forEachTerminalSlot(screen, (slot, stack) -> {
			if (!stack.hasFoil() && ItemNames.cleanForSearch(
							stack.getHoverName().getString())
					.toLowerCase(Locale.ROOT).startsWith(prefix)) {
				wash(extractor, screen, slot, HIGHLIGHT);
			}
		});
	}

	/** Un-glinted items of the named colour want clicks. */
	private static void drawColorMatch(AbstractContainerScreen<?> screen,
			GuiGraphicsExtractor extractor, String colorWord) {
		// The registry id spells colours with underscores ("light_gray"); the
		// title spells them with spaces, and calls light gray SILVER.
		String needle = colorWord.trim().toLowerCase(Locale.ROOT).replace(' ', '_');

		if (needle.equals("silver")) {
			needle = "light_gray";
		}

		String finalNeedle = needle;

		forEachTerminalSlot(screen, (slot, stack) -> {
			String path = idPath(stack);

			// "red_..." matches, "..._red_..." matches; "jasper" does not.
			if (!stack.hasFoil() && (path.startsWith(finalNeedle + "_")
					|| path.contains("_" + finalNeedle + "_"))) {
				wash(extractor, screen, slot, HIGHLIGHT);
			}
		});
	}

	/**
	 * Rubix: panes cycle red-orange-yellow-green-blue; pick the colour the
	 * board reaches in the fewest clicks and print each pane's count -
	 * positive for left clicks (forward), negative for right (back).
	 */
	private static void drawRubix(AbstractContainerScreen<?> screen,
			GuiGraphicsExtractor extractor) {
		int[] totals = new int[RUBIX_ORDER.length];

		for (int target = 0; target < RUBIX_ORDER.length; target++) {
			for (Slot slot : screen.getMenu().slots) {
				int index = rubixIndex(slot.getItem());

				if (isTerminalSlot(screen, slot) && index >= 0) {
					totals[target] += Math.abs(clicksTo(index, target));
				}
			}
		}

		int best = 0;

		for (int target = 1; target < totals.length; target++) {
			if (totals[target] < totals[best]) {
				best = target;
			}
		}

		for (Slot slot : screen.getMenu().slots) {
			int index = rubixIndex(slot.getItem());

			if (!isTerminalSlot(screen, slot) || index < 0) {
				continue;
			}

			int clicks = clicksTo(index, best);

			if (clicks != 0) {
				wash(extractor, screen, slot, HIGHLIGHT_SOFT);
				extractor.text(net.minecraft.client.Minecraft.getInstance().font,
						(clicks > 0 ? "+" : "") + clicks,
						screen.leftPos + slot.x + 4,
						screen.topPos + slot.y + 4,
						0xFFFFFFFF, true);
			}
		}
	}

	/** Signed clicks moving a pane to the target colour, shortest way round. */
	private static int clicksTo(int from, int target) {
		int forward = Math.floorMod(target - from, RUBIX_ORDER.length);
		int back = Math.floorMod(from - target, RUBIX_ORDER.length);
		return forward <= back ? forward : -back;
	}

	private static int rubixIndex(ItemStack stack) {
		String path = idPath(stack);

		for (int i = 0; i < RUBIX_ORDER.length; i++) {
			if (path.startsWith(RUBIX_ORDER[i] + "_")) {
				return i;
			}
		}

		return -1;
	}

	private static void forEachTerminalSlot(AbstractContainerScreen<?> screen,
			java.util.function.BiConsumer<Slot, ItemStack> action) {
		for (Slot slot : screen.getMenu().slots) {
			if (isTerminalSlot(screen, slot) && !slot.getItem().isEmpty()) {
				action.accept(slot, slot.getItem());
			}
		}
	}

	/** The terminal's own slots - the player inventory below is never marked. */
	private static boolean isTerminalSlot(AbstractContainerScreen<?> screen, Slot slot) {
		int containerSlots = screen.getMenu().slots.size() - 36;
		return slot.index < containerSlots;
	}

	private static void wash(GuiGraphicsExtractor extractor,
			AbstractContainerScreen<?> screen, Slot slot, int color) {
		int x = screen.leftPos + slot.x;
		int y = screen.topPos + slot.y;
		extractor.fill(x, y, x + 16, y + 16, color);
	}

	private static String idPath(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
	}

	/** For /skyaid dump: the exact title and grid of the open terminal. */
	public static void dumpInto(StringBuilder out) {
		out.append("\nTERMINAL:\n");
		out.append("  last terminal title: ").append(lastTitle).append('\n');

		var client = net.minecraft.client.Minecraft.getInstance();

		if (client.gui != null
				&& client.gui.screen() instanceof AbstractContainerScreen<?> open) {
			out.append("  open container: ").append(open.getTitle().getString()).append('\n');

			for (Slot slot : open.getMenu().slots) {
				if (slot.index >= open.getMenu().slots.size() - 36
						|| slot.getItem().isEmpty()) {
					continue;
				}

				out.append("    ").append(slot.index).append(": ")
						.append(idPath(slot.getItem()))
						.append(" x").append(slot.getItem().getCount())
						.append(slot.getItem().hasFoil() ? " (glint)" : "")
						.append(" \"").append(slot.getItem().getHoverName().getString())
						.append("\"\n");
			}
		}
	}
}
