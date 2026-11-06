package com.flintclient.client.control.feature;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who plays on Flint, and the Flint mark each wears before their name on nametags: everyone on Flint has the default
 * mark; content creators, staff, developers and the owners are given theirs by Flint. You are always on Flint (your
 * mark is the default until Flint gives you another). Nobody else is known yet: telling Flint players apart needs a
 * Flint presence service, which doesn't exist; this is where it would report them. Render thread; pure Java.
 */
public final class FlintPlayers {
	/** The marks, in the order of their glyphs in Flint's badge font ({@code flintclient:badge}, from U+E000). */
	public enum Tag {
		DEFAULT,
		CONTENT_CREATOR,
		STAFF,
		DEVELOPER,
		CO_OWNER,
		OWNER;

		/** Its glyph in the badge font. */
		public char glyph() {
			return (char) (0xE000 + ordinal());
		}
	}

	private static final Map<UUID, Tag> KNOWN = new HashMap<>();
	private static Tag own = Tag.DEFAULT;

	private FlintPlayers() {
	}

	/** Another player's mark, or null when Flint doesn't know them to be on Flint. */
	public static @Nullable Tag tag(UUID player) {
		return KNOWN.get(player);
	}

	/** Your own mark. */
	public static Tag own() {
		return own;
	}

	public static void setOwn(Tag tag) {
		own = tag;
	}

	/** {@link #setOwn} by the tag's name ("owner", "content_creator"): for Flint's tools and tours. Unknown names change nothing. */
	public static void setOwnNamed(String name) {
		for (Tag t : Tag.values()) {
			if (t.name().equals(name.toUpperCase(Locale.ROOT))) {
				own = t;
			}
		}
	}

	public static void set(UUID player, Tag tag) {
		KNOWN.put(player, tag);
	}

	public static void remove(UUID player) {
		KNOWN.remove(player);
	}

	/** On leaving a world or server: whoever was known there may not be elsewhere. */
	public static void clear() {
		KNOWN.clear();
	}
}
