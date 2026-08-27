package dev.skyaid.config;

import dev.skyaid.api.ApiStatus;
import dev.skyaid.api.HypixelApiClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A dedicated popup for entering the Hypixel API key.
 *
 * <p>This is the preferred way to set the key, in place of typing it as a command.
 * Anything typed into the chat box goes into chat history, and a badly mistyped
 * command gets sent to the server as public chat - which would leak the key.
 * Nothing typed here can reach the server or chat history at all.
 *
 * <p>The field is masked as you type, there is a paste button so the key need
 * never be visible, and the key is checked against Hypixel before you leave.
 */
public class ApiKeyScreen extends Screen {
	private static final Pattern KEY_SHAPE = Pattern.compile(
			"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
			Pattern.CASE_INSENSITIVE);

	private static final String DASHBOARD_URL = "https://developer.hypixel.net/dashboard";

	private static final int WIDTH = 240;
	private static final int HEIGHT = 20;
	private static final int GAP = 24;

	/** Height reserved for the status line, which is text rather than a widget. */
	private static final int STATUS_ROW = 16;

	private final Screen parent;

	private EditBox keyBox;
	private int statusY;
	private Component status = Component.empty();

	public ApiKeyScreen(Screen parent) {
		super(Component.literal("Hypixel API key"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDTH / 2;
		int y = this.height / 2 - 46;

		keyBox = new EditBox(this.font, x, y, WIDTH, HEIGHT, Component.literal("API key"));
		keyBox.setMaxLength(64);
		keyBox.setHint(Component.literal("paste your key here"));
		keyBox.setValue(ConfigManager.get().hypixelApiKey);
		// Masked so the key is never on screen, even while typing.
		keyBox.addFormatter((text, offset) ->
				FormattedCharSequence.forward(bullets(text.length()), Style.EMPTY));
		addRenderableWidget(keyBox);
		setInitialFocus(keyBox);

		// This screen is the only place key state is shown, so say up front whether
		// one is already stored rather than leaving a row of bullets to interpret.
		if (status.getString().isEmpty()) {
			status = ConfigManager.get().hypixelApiKey.isBlank()
					? ApiStatus.needsKey()
					: Component.literal("A key is stored. Save and check to verify it.")
							.withStyle(ChatFormatting.GRAY);
		}

		y += GAP;

		int half = WIDTH / 2 - 2;

		addRenderableWidget(Button.builder(
						Component.literal("Paste"), button -> pasteFromClipboard())
				.bounds(x, y, half, HEIGHT)
				.build());

		addRenderableWidget(Button.builder(
						Component.literal("Get a key"), button -> Util.getPlatform().openUri(DASHBOARD_URL))
				.bounds(x + half + 4, y, half, HEIGHT)
				.build());

		y += GAP;

		addRenderableWidget(Button.builder(
						Component.literal("Save and check"), button -> save())
				.bounds(x, y, half, HEIGHT)
				.build());

		addRenderableWidget(Button.builder(
						Component.literal("Clear"), button -> confirmClear())
				.bounds(x + half + 4, y, half, HEIGHT)
				.build());

		// A whole row is left blank here for the status line, which is drawn rather
		// than being a widget - without the gap it landed on top of Done.
		statusY = y + GAP + 6;
		y += GAP + STATUS_ROW;

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.bounds(x, y, WIDTH, HEIGHT)
				.build());
	}

	/**
	 * Clearing is irreversible from here - the key is not recoverable from the mod
	 * once forgotten, only from the Hypixel dashboard - so it asks first.
	 */
	private void confirmClear() {
		if (ConfigManager.get().hypixelApiKey.isBlank() && keyBox.getValue().isBlank()) {
			status = Component.literal("There is no key to clear.").withStyle(ChatFormatting.GRAY);
			return;
		}

		this.minecraft.setScreenAndShow(new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						clearKey();
					}

					// Either way, come back to this screen rather than the game.
					this.minecraft.setScreenAndShow(this);
				},
				Component.literal("Clear your Hypixel API key?"),
				Component.literal(
						"SkyAid will forget it. You can paste it again, or make a new one "
								+ "at developer.hypixel.net."),
				Component.literal("Clear key"),
				CommonComponents.GUI_CANCEL));
	}

	private void clearKey() {
		keyBox.setValue("");
		ConfigManager.get().hypixelApiKey = "";
		ConfigManager.save();
		status = Component.literal("Key cleared.").withStyle(ChatFormatting.YELLOW);
	}

	private void pasteFromClipboard() {
		String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();

		if (clipboard == null || clipboard.isBlank()) {
			status = Component.literal("Clipboard is empty.").withStyle(ChatFormatting.RED);
			return;
		}

		keyBox.setValue(clipboard.trim());
		status = Component.literal("Pasted. Press Save and check.")
				.withStyle(ChatFormatting.GRAY);
	}

	private void save() {
		String key = keyBox.getValue().trim();

		if (key.isEmpty()) {
			status = Component.literal("Enter a key first.").withStyle(ChatFormatting.RED);
			return;
		}

		if (!KEY_SHAPE.matcher(key).matches()) {
			status = Component.literal("That is not a key - it should be a UUID.")
					.withStyle(ChatFormatting.RED);
			return;
		}

		ConfigManager.get().hypixelApiKey = key;
		ConfigManager.get().apiKeyNoticeDismissed = true;
		ConfigManager.save();

		status = Component.literal("Saved " + masked(key) + ", checking...")
				.withStyle(ChatFormatting.GRAY);

		HypixelApiClient.checkKey().thenAccept(result ->
				Minecraft.getInstance().execute(() -> showResult(result)));
	}

	/** Same wording as /skyaid key status, so the two never disagree. */
	private void showResult(HypixelApiClient.KeyCheck result) {
		status = ApiStatus.of(result);
	}

	private static String bullets(int length) {
		// '#' rather than a bullet dot: it is the same width as the key's own
		// characters, so the selection highlight lines up with the mask instead
		// of stretching past it as a blank white block.
		return "#".repeat(length);
	}

	private static String masked(String key) {
		return bullets(4) + key.substring(key.length() - 4).toLowerCase(Locale.ROOT);
	}

	@Override
	public void extractRenderState(
			GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(extractor, mouseX, mouseY, partialTick);

		extractor.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 70,
				0xFFFFFFFF);
		extractor.centeredText(this.font,
				Component.literal("Optional - only /skyaid stats needs a key")
						.withStyle(ChatFormatting.DARK_GRAY),
				this.width / 2, this.height / 2 - 58, 0xFFAAAAAA);
		extractor.centeredText(this.font, this.status, this.width / 2, statusY, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		ConfigManager.save();
		this.minecraft.setScreenAndShow(parent);
	}
}
