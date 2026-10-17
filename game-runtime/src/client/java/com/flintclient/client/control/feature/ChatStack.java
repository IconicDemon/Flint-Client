package com.flintclient.client.control.feature;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Stacking repeated chat messages: a message equal (as plain text) to one shown recently replaces it, with a count. Only
 * the last {@link #WINDOW} messages are remembered, and with "consecutive only" just the latest. The shown message is
 * remembered by identity, so the chat can find and remove the earlier copy. Pure Java.
 */
public final class ChatStack {
	static final int WINDOW = 20;

	private record Entry(String plain, Object shown, int count) {
	}

	private final List<Entry> recent = new ArrayList<>();
	private int count = 1;

	/**
	 * A message with plain text {@code plain} arrives: returns the earlier shown message it repeats (to remove), or null.
	 * {@link #count()} then says how many times it has been seen in a row.
	 */
	public @Nullable Object accept(String plain, boolean consecutiveOnly) {
		int limit = consecutiveOnly ? Math.min(1, recent.size()) : recent.size();
		for (int i = 0; i < limit; i++) {
			Entry entry = recent.get(i);
			if (entry.plain.equals(plain)) {
				recent.remove(i);
				count = entry.count + 1;
				return entry.shown;
			}
		}
		count = 1;
		return null;
	}

	/** How many times the last accepted message has been seen. */
	public int count() {
		return count;
	}

	/** Remembers what was shown for the last accepted message. */
	public void shown(String plain, Object shown) {
		recent.addFirst(new Entry(plain, shown, count));
		while (recent.size() > WINDOW) {
			recent.removeLast();
		}
	}

	public void clear() {
		recent.clear();
		count = 1;
	}
}
