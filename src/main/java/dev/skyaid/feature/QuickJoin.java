package dev.skyaid.feature;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * A "Join Hypixel" button on the title screen: one click from game open to
 * the lobby, instead of Multiplayer, scroll, double-click. Connecting to a
 * server the player clicked for is exactly what the multiplayer screen does -
 * nothing here acts on its own.
 */
public final class QuickJoin {
	private static final String HYPIXEL = "mc.hypixel.net";

	private QuickJoin() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			// The vanilla title screen gets a full-width button; under Dawn
			// that screen never shows (Feather draws its own proprietary
			// menu), so the MULTIPLAYER screen - vanilla, and one click from
			// Dawn's menu - carries a corner button too. Feather's own
			// screens get a best-effort corner button as well: if their UI
			// draws over vanilla widgets it simply never shows, and the
			// class-name log below is what tells us what their menu really
			// is (the button belongs on the HOME menu).
			String owner = screen.getClass().getName();

			if (!(screen instanceof
					net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) {
				dev.skyaid.SkyAidClient.LOGGER.info("menu screen: {}", owner);
			}

			// Title screen: Join Hypixel belongs IN the main list and
			// the Realms button gone - so it simply takes the Realms slot:
			// same row, same width, nothing else moves. Realms is found by
			// its translation key, never by its displayed text.
			if (screen instanceof TitleScreen) {
				var widgets = Screens.getWidgets(screen);
				net.minecraft.client.gui.components.AbstractWidget realms = null;

				for (var widget : widgets) {
					if (widget.getMessage().getContents() instanceof
							net.minecraft.network.chat.contents.TranslatableContents text
							&& text.getKey().equals("menu.online")) {
						realms = widget;
						break;
					}
				}

				if (realms == null) {
					return; // an unfamiliar layout - better no button than a wrong one
				}

				int x = realms.getX();
				int y = realms.getY();
				int rowWidth = realms.getWidth();
				widgets.remove(realms);
				widgets.add(joinButton(client, screen, x, y, rowWidth));

				// Realms lives on as a small square at the end of the icon
				// row (skin, language, accessibility), opening the same
				// Realms screen the big button did.
				int iconRight = Integer.MIN_VALUE;
				int iconY = -1;

				for (var widget : widgets) {
					if (widget.getWidth() == 20 && widget.getHeight() == 20
							&& widget.getY() > y && widget.getX() > iconRight) {
						iconRight = widget.getX();
						iconY = widget.getY();
					}
				}

				if (iconY >= 0) {
					widgets.add(Button.builder(Component.literal("R"),
									button -> client.setScreenAndShow(
											new com.mojang.realmsclient.RealmsMainScreen(screen)))
							.bounds(iconRight + 24, iconY, 20, 20)
							.tooltip(net.minecraft.client.gui.components.Tooltip.create(
									realms.getMessage()))
							.build());

					// Four icons now instead of three: re-centre the whole row
					// (20px squares on a 24px pitch) on the screen's middle.
					java.util.List<net.minecraft.client.gui.components.AbstractWidget> row =
							new java.util.ArrayList<>();

					for (var widget : widgets) {
						if (widget.getWidth() == 20 && widget.getHeight() == 20
								&& widget.getY() == iconY) {
							row.add(widget);
						}
					}

					row.sort(java.util.Comparator.comparingInt(
							net.minecraft.client.gui.components.AbstractWidget::getX));
					int span = (row.size() - 1) * 24 + 20;
					int startX = width / 2 - span / 2;

					for (int i = 0; i < row.size(); i++) {
						row.get(i).setX(startX + i * 24);
					}
				}
			} else if (owner.startsWith("gg.dawn.") && client.level == null) {
				// Dawn's home menu is gg.dawn.feather.<obfuscated>, a real
				// vanilla Screen subclass (log-verified). Out-of-world only,
				// so Dawn's in-game settings screens stay untouched. (The
				// multiplayer-screen corner button was removed at the user's
				// request - the main-list button covers vanilla.)
				Screens.getWidgets(screen).add(joinButton(client, screen,
						width - 105, 5, 100));
			}
		});
	}

	private static Button joinButton(net.minecraft.client.Minecraft client,
			net.minecraft.client.gui.screens.Screen screen, int x, int y, int width) {
		return Button.builder(
						Component.literal("Join Hypixel"),
						button -> ConnectScreen.startConnecting(
								screen, client,
								ServerAddress.parseString(HYPIXEL),
								new ServerData("Hypixel", HYPIXEL,
										ServerData.Type.OTHER),
								false, null))
				.bounds(x, y, width, 20)
				.build();
	}
}
