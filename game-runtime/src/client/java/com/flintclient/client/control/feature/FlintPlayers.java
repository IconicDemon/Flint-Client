package com.flintclient.client.control.feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who plays on Flint, and the Flint mark each wears before their name on nametags: everyone on Flint has the default
 * mark; content creators, staff, developers and the owners are given theirs by Flint. You are always on Flint (your
 * mark is the default until Flint gives you another). Flint's presence service reports the others. Your own marks come
 * from the Flint app when it has their list (the website's {@code /me}: {@link #fromLauncher}); the one you pick is then
 * saved by the Store, so other players see it. Without that list, the marks are worked out here and the pick stays in this
 * game. Render thread; pure Java.
 */
public final class FlintPlayers {
	/**
	 * The marks, in the order of their glyphs in Flint's badge font ({@code flintclient:badge}, from U+E000); new marks are
	 * added at the end so every glyph keeps its place. The pictures are the owner's v3 icon set, the same files Flint Admin
	 * and the Creator portal show. A tag name the game doesn't know (a newer mark) shows as the default mark.
	 */
	public enum Tag {
		DEFAULT,
		CONTENT_CREATOR,
		STAFF,
		DEVELOPER,
		CO_OWNER,
		OWNER,
		/** Creators on trial in the Flint Creator Program. */
		TRIAL_CREATOR,
		/** Flint's brand partners. */
		VIP,
		/** Friends of Flint. */
		SPECIAL;

		/** Its glyph in the badge font. */
		public char glyph() {
			return (char) (0xE000 + ordinal());
		}

		/** Its name as the Store writes it ("content_creator"). */
		public String key() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private static final Map<UUID, Tag> KNOWN = new HashMap<>();
	private static Tag own = Tag.DEFAULT;
	/** The mark you chose to wear among yours (Cosmetics → Badges), or null for the one Flint gives you. */
	private static @Nullable Tag chosen;
	/**
	 * The marks the account has as the Flint app lists them (the website's {@code /me} {@code badges}: the Flint mark first,
	 * then the Store's order), or null when the app has no list (an older app, or a Store without badges).
	 */
	private static @Nullable List<Tag> launcherOwned;
	/** The mark the Store says others see now ({@code /me} {@code badge}), with {@link #launcherOwned}. */
	private static Tag launcherWorn = Tag.DEFAULT;

	private FlintPlayers() {
	}

	/** Another player's mark, or null when Flint doesn't know them to be on Flint. */
	public static @Nullable Tag tag(UUID player) {
		return KNOWN.get(player);
	}

	/** Your own mark (the one Flint's presence service says you have). */
	public static Tag own() {
		return own;
	}

	public static void setOwn(Tag tag) {
		own = tag;
	}

	/**
	 * The marks you have: the Flint app's list when it gave one, else the one Flint gives you first and the Flint mark every
	 * player has.
	 */
	public static List<Tag> owned() {
		List<Tag> fromApp = launcherOwned;
		if (fromApp != null) {
			return fromApp;
		}
		return own == Tag.DEFAULT ? List.of(Tag.DEFAULT) : List.of(own, Tag.DEFAULT);
	}

	/**
	 * The mark you wear on your own nametag: the one you chose among yours, else the one Flint gives you (with the app's
	 * list: the one the Store says others see). A choice of a mark that is no longer yours (Flint took it away) falls back.
	 */
	public static Tag worn() {
		Tag c = chosen;
		List<Tag> fromApp = launcherOwned;
		if (fromApp != null) {
			return c != null && fromApp.contains(c) ? c : launcherWorn;
		}
		return c != null && owned().contains(c) ? c : own;
	}

	/** Wears {@code tag}, one of {@link #owned}; any other changes nothing. With the app's list it shows until the Store answers. */
	public static void wear(Tag tag) {
		if (owned().contains(tag)) {
			chosen = launcherOwned == null && tag == own ? null : tag;
		}
	}

	/** Whether the Flint app gave the list of marks: a pick is then saved by the Store, through the app. */
	public static boolean fromLauncher() {
		return launcherOwned != null;
	}

	/**
	 * The Flint app's list of the account's marks and the one others see ({@code /me} {@code badges} and {@code badge}, by
	 * name). Names this game doesn't know are left out; the Flint mark is always there. A null list (the app or the Store
	 * has none) goes back to the marks worked out here. The Store's answer replaces a pick on its way.
	 */
	public static void fromLauncher(@Nullable List<String> badges, @Nullable String badge) {
		if (badges == null) {
			clearLauncher();
			return;
		}
		List<Tag> tags = new ArrayList<>();
		tags.add(Tag.DEFAULT);
		for (String name : badges) {
			Tag t = name == null ? null : named(name);
			if (t != null && !tags.contains(t)) {
				tags.add(t);
			}
		}
		launcherOwned = List.copyOf(tags);
		Tag b = badge == null ? null : named(badge);
		launcherWorn = b != null && tags.contains(b) ? b : Tag.DEFAULT;
		chosen = null;
	}

	/** Back to the marks worked out here (no list from the Flint app). */
	public static void clearLauncher() {
		launcherOwned = null;
		launcherWorn = Tag.DEFAULT;
		chosen = null;
	}

	/** The pick made in this game that the Store hasn't answered yet, or null: put back with {@link #restorePick}. */
	public static @Nullable Tag pick() {
		return chosen;
	}

	/** Puts a pick back ({@link #pick}) after the Store refused a newer one; null: what the Store says. */
	public static void restorePick(@Nullable Tag tag) {
		chosen = tag;
	}

	/** A mark by its name ("content_creator"), or null for one this game doesn't know. */
	public static @Nullable Tag named(String name) {
		for (Tag t : Tag.values()) {
			if (t.name().equals(name.toUpperCase(Locale.ROOT))) {
				return t;
			}
		}
		return null;
	}

	/** {@link #setOwn} by the tag's name ("owner", "content_creator"): for Flint's tools and tours. Unknown names change nothing. */
	public static void setOwnNamed(String name) {
		Tag t = named(name);
		if (t != null) {
			own = t;
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

	/** Tests and an account change: the choice goes back to the mark Flint gives, and the app's list (another account's) goes. */
	public static void forgetChoice() {
		chosen = null;
		launcherOwned = null;
		launcherWorn = Tag.DEFAULT;
	}
}
