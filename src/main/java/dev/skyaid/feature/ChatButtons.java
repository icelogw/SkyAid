package dev.skyaid.feature;

import dev.skyaid.config.ConfigManager;
import dev.skyaid.core.HypixelDetector;
import dev.skyaid.parse.FormatCodes;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A clickable [stats] tag on party join messages, so checking a Party Finder
 * joiner is one click instead of typing their name. The click runs
 * {@code /skyaid stats <name>} - a CLIENT command that never reaches the
 * server, so this stays comfortably inside display-only.
 *
 * <p>The join wordings are ecosystem knowledge, unverified against captures:
 * a miss means no tag appears, nothing worse.
 */
public final class ChatButtons {
	private static final Pattern PARTY_FINDER_JOIN = Pattern.compile(
			"^Party Finder > (\\w{2,16}) joined the dungeon group!.*$");
	private static final Pattern PARTY_JOIN = Pattern.compile(
			"^(?:\\[[^\\]]+\\] )?(\\w{2,16}) joined the party\\.?$");
	private static final Pattern JOINED_THEIR_PARTY = Pattern.compile(
			"^You have joined (?:\\[[^\\]]+\\] )?(\\w{2,16})'s party!$");

	/** A line that is nothing but a (possibly ranked) player name. */
	private static final Pattern BARE_NAME = Pattern.compile(
			"^(?:\\[[^\\]]+\\] )?(\\w{2,16})$");

	/**
	 * The name line of Hypixel's social-options popup, remembered so the
	 * button row that follows it knows who it is about.
	 */
	private static String lastBareName;

	private ChatButtons() {
	}

	public static void register() {
		ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
			if (overlay || !ConfigManager.get().enabled
					|| !HypixelDetector.isOnHypixel()) {
				return message;
			}

			String text = FormatCodes.strip(message.getString()).trim();

			// Hypixel's social-options popup (click a name in chat): the name
			// on one line, "[Report Player] [Block Player]" on the next. A
			// [Lookup] joins the row - one click, one CLIENT command.
			if (text.contains("[Report Player]") || text.contains("[Block Player]")) {
				String target = socialName(text);

				if (target == null) {
					target = lastBareName;
				}

				// Field-debug trail: the popup reached us, and this is what
				// it looked like - the line that explains a missing button.
				dev.skyaid.SkyAidClient.LOGGER.info(
						"Social options: target={} text=\"{}\"", target, text);

				return target == null ? message
						: message.copy().append(lookupButton("  [Lookup]", target));
			}

			Matcher bare = BARE_NAME.matcher(text);

			if (bare.matches()) {
				lastBareName = bare.group(1);
				dev.skyaid.SkyAidClient.LOGGER.info(
						"Social options: remembered name {}", lastBareName);
				return message;
			}

			String name = joinerName(text);

			if (name == null) {
				return message;
			}

			return message.copy().append(lookupButton(" [stats]", name));
		});
	}

	/** The popup's own name line, when name and buttons share one message. */
	private static String socialName(String text) {
		for (String line : text.split("\n")) {
			Matcher bare = BARE_NAME.matcher(line.trim());

			if (bare.matches()) {
				return bare.group(1);
			}
		}

		return null;
	}

	private static Component lookupButton(String label, String name) {
		return Component.literal(label).withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand("/skyaid stats " + name))
				.withHoverEvent(new HoverEvent.ShowText(
						Component.literal("Look up " + name + "'s stats")
								.withStyle(ChatFormatting.GRAY))));
	}

	private static String joinerName(String text) {
		Matcher finder = PARTY_FINDER_JOIN.matcher(text);

		if (finder.matches()) {
			return finder.group(1);
		}

		Matcher join = PARTY_JOIN.matcher(text);

		if (join.matches()) {
			return join.group(1);
		}

		Matcher theirs = JOINED_THEIR_PARTY.matcher(text);
		return theirs.matches() ? theirs.group(1) : null;
	}
}
