package dev.skyaid.feature;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The number-entry replacement for Hypixel's price and amount signs (bazaar
 * order price, order amount, auction starting bid): one clear text box, the
 * sign's own hint lines shown so the question stays visible, and a live
 * preview that spells out shorthand - typing "1.5m" shows "= 1,500,000".
 *
 * <p>Submitting writes the text into the hidden sign's first line and sends
 * the update exactly as vanilla's Done would; the value itself goes through
 * verbatim, so whatever shorthand Hypixel accepts on the sign works here too.
 */
public class MarketNumberScreen extends Screen {
	private static final int WIDTH = 200;

	private final AbstractSignEditScreen sign;
	private final String[] hints;

	private EditBox value;

	public MarketNumberScreen(AbstractSignEditScreen sign, String[] hints) {
		super(Component.literal("Enter a value"));
		this.sign = sign;
		this.hints = hints;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDTH / 2;
		int y = this.height / 2 - 30;

		value = new EditBox(this.font, x, y, WIDTH, 20, Component.literal("Value"));
		value.setMaxLength(16);
		value.setHint(Component.literal("10k, 1.5m, 250..."));
		addRenderableWidget(value);

		addRenderableWidget(Button.builder(Component.literal("Submit"),
						button -> submit())
				.bounds(x, y + 46, WIDTH / 2 - 2, 20)
				.build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL,
						button -> onClose())
				.bounds(x + WIDTH / 2 + 2, y + 46, WIDTH / 2 - 2, 20)
				.build());

		setInitialFocus(value);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
		if (key.key() == 257 || key.key() == 335) { // enter submits
			submit();
			return true;
		}

		return super.keyPressed(key);
	}

	private void submit() {
		// Digits and the shorthand Hypixel's signs accept - nothing else, so
		// no character the chat validator objects to can go out.
		String text = value.getValue().replaceAll("[^0-9.,kKmMbB]", "").trim();

		if (text.isEmpty()) {
			onClose();
			return;
		}

		SignSearchAssist.submitNumber(sign, text);
		this.minecraft.setScreenAndShow(null);
	}

	@Override
	public void extractRenderState(
			GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		int x = this.width / 2 - WIDTH / 2;
		int y = this.height / 2 - 30;
		int left = x - 10;
		int top = y - 58;
		int right = x + WIDTH + 10;
		int bottom = y + 76;

		// The same bevelled vanilla window as the search screen.
		extractor.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF000000);
		extractor.fill(left, top, right, bottom, 0xFFC6C6C6);
		extractor.fill(left, top, right, top + 2, 0xFFFFFFFF);
		extractor.fill(left, top, left + 2, bottom, 0xFFFFFFFF);
		extractor.fill(left, bottom - 2, right, bottom, 0xFF555555);
		extractor.fill(right - 2, top, right, bottom, 0xFF555555);

		super.extractRenderState(extractor, mouseX, mouseY, partialTick);

		// The sign's own hint lines are the question ("Enter amount", "Your
		// auction starting bid") - dark text, no shadow, on the light body.
		int hintY = top + 9;

		for (String hint : hints) {
			if (hint != null && !hint.isBlank() && !hint.startsWith("^")) {
				extractor.text(this.font, hint,
						this.width / 2 - this.font.width(hint) / 2, hintY, 0xFF404040, false);
				hintY += 11;
			}
		}

		// The live preview: what the shorthand means in whole coins.
		long parsed = parseShorthand(value == null ? "" : value.getValue());

		if (parsed > 0) {
			String preview = "= " + dev.skyaid.parse.Numbers.group(parsed);
			extractor.text(this.font, preview,
					this.width / 2 - this.font.width(preview) / 2, y + 28, 0xFF2A6A2A, false);
		}
	}

	/** "1.5m" -> 1,500,000; "10k" -> 10,000; "250" -> 250; 0 when unparsable. */
	static long parseShorthand(String text) {
		String cleaned = text.trim().toLowerCase(Locale.ROOT).replace(",", "");

		if (cleaned.isEmpty()) {
			return 0;
		}

		double multiplier = 1;
		char last = cleaned.charAt(cleaned.length() - 1);

		if (last == 'k') {
			multiplier = 1_000;
		} else if (last == 'm') {
			multiplier = 1_000_000;
		} else if (last == 'b') {
			multiplier = 1_000_000_000;
		}

		if (multiplier > 1) {
			cleaned = cleaned.substring(0, cleaned.length() - 1);
		}

		try {
			return Math.round(Double.parseDouble(cleaned) * multiplier);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Closing without a value answers the hidden sign untouched. */
	@Override
	public void onClose() {
		SignSearchAssist.cancelSign(sign);
		this.minecraft.setScreenAndShow(null);
	}
}
