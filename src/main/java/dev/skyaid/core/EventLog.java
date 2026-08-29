package dev.skyaid.core;

import dev.skyaid.SkyAidClient;
import dev.skyaid.config.ConfigManager;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * A small ring buffer of the decisions SkyAid makes - a museum piece marked
 * deposited, a bazaar order captured, a Jacob fetch failing - printed by
 * {@code /skyaid dump}. A bug report with a dump then carries the recent
 * history that explains it, without asking anyone to reproduce with logging
 * on. Debug mode mirrors every event to the game log as it happens.
 */
public final class EventLog {
	private static final int CAPACITY = 150;

	private record Event(long at, String tag, String message) {
	}

	private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>(CAPACITY);

	private EventLog() {
	}

	/** Remembers one event; cheap enough for any non-per-frame call site. */
	public static synchronized void event(String tag, String message) {
		if (EVENTS.size() >= CAPACITY) {
			EVENTS.removeFirst();
		}

		EVENTS.addLast(new Event(System.currentTimeMillis(), tag, message));

		if (ConfigManager.get().debug) {
			SkyAidClient.LOGGER.info("[{}] {}", tag, message);
		}
	}

	/** The buffer, oldest first, with ages - for the dump. */
	public static synchronized void dumpInto(StringBuilder out) {
		out.append("\nRECENT EVENTS (newest last):\n");

		if (EVENTS.isEmpty()) {
			out.append("  (none this session)\n");
			return;
		}

		long now = System.currentTimeMillis();

		for (Event event : EVENTS) {
			long seconds = (now - event.at()) / 1000;
			out.append(String.format(Locale.ROOT, "  %5ds ago  [%s] %s%n",
					seconds, event.tag(), event.message()));
		}
	}
}
