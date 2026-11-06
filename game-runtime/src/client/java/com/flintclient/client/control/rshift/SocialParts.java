package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.platform.Faces;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.TextField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the Friends and Cosmetics windows share: status colours and words, the times friends were last seen, Flint's error
 * codes in words (the app's own, from its English strings), players' faces, wrapped text in the menu's type, and the small
 * controls (a button in the window's look, a text field). Sizes in design pixels ({@link MenuStyle#d}).
 */
final class SocialParts {
	private SocialParts() {
	}

	// ---------- statuses ----------

	static final int ONLINE = 0xFF3FBF4A;
	static final int BUSY = 0xFFE5534B;
	static final int AWAY = 0xFFE3B23C;

	static int statusColor(String status) {
		return switch (status) {
			case "online" -> ONLINE;
			case "busy" -> BUSY;
			case "away" -> AWAY;
			default -> MenuStyle.NEUTRAL;
		};
	}

	static String statusWord(String status) {
		return switch (status) {
			case "online" -> "Online";
			case "busy" -> "Busy";
			case "away" -> "Away";
			case "invisible" -> "Invisible";
			default -> "Offline";
		};
	}

	/** A friend's status line: "In game" while they play, "Offline · 2 h ago" when they were seen. */
	static String statusLine(String status, boolean inGame, long lastSeen, long now) {
		if ("offline".equals(status)) {
			return lastSeen > 0 ? "Offline · " + ago(lastSeen, now) : "Offline";
		}
		return inGame ? "In game" : statusWord(status);
	}

	/** "just now", "5 min ago", "2 h ago", "3 d ago". */
	static String ago(long at, long now) {
		long seconds = Math.max(0, (now - at) / 1000);
		if (seconds < 60) {
			return "just now";
		}
		if (seconds < 3600) {
			return seconds / 60 + " min ago";
		}
		if (seconds < 86_400) {
			return seconds / 3600 + " h ago";
		}
		return seconds / 86_400 + " d ago";
	}

	/** A dot of a status's colour, {@code size} units across, centred on ({@code cx}, {@code cy}). */
	static void dot(Canvas c, float cx, float cy, float size, int argb) {
		MenuStyle.box(c, cx - size / 2, cy - size / 2, size, size, size / 2, argb);
	}

	// ---------- Flint's codes in words ----------

	private static final Map<String, String> ERRORS = Map.ofEntries(
		Map.entry("offline", "Flint can't reach Friends right now. Check your connection; Flint keeps trying."),
		Map.entry("not_on_flint", "That player isn't on Flint yet. They need to open Flint once before you can add them."),
		Map.entry("not_accepting", "They aren't accepting friend requests right now."),
		Map.entry("already_friends", "You're already friends."),
		Map.entry("already_sent", "You've already sent them a request."),
		Map.entry("self", "That's you."),
		Map.entry("too_many", "You have 200 friends, the most Flint allows. Remove someone to make room."),
		Map.entry("rate_limited", "Slow down a little. Try again in a moment."),
		Map.entry("not_found", "That request isn't there any more."),
		Map.entry("not_friends", "You aren't friends any more."),
		Map.entry("empty", "Write something first."),
		Map.entry("too_long", "Messages can be up to 1,000 characters."),
		Map.entry("mojang", "Mojang didn't confirm your Minecraft account. Refresh it in the Flint app (Settings → Accounts), then try again."),
		Map.entry("unverified", "Mojang didn't confirm your Minecraft account. Refresh it in the Flint app (Settings → Accounts), then try again."),
		Map.entry("account", "Flint is using another account than this game. Switch accounts so both match."),
		Map.entry("signed_out", "Sign in with Microsoft in the Flint app to use Friends."),
		Map.entry("unreachable", "Flint isn't running. Open the Flint app and try again."),
		Map.entry("timeout", "Flint took too long to answer. Try again."),
		Map.entry("expired", "Your Friends sign-in ended. Flint signs in again by itself; try again in a moment."),
		Map.entry("invalid_status", "That status isn't one Flint knows."));

	/** What a Flint error code says (the app's own words); anything else is the generic "didn't work". */
	static String errorText(@Nullable String code) {
		return code == null ? "That didn't work. Try again." : ERRORS.getOrDefault(code, "That didn't work. Try again.");
	}

	// ---------- faces ----------

	/** A player's face, {@code size} units square at ({@code x}, {@code y}), sharp at whole multiples of its 8 pixels. */
	static void face(Canvas c, @Nullable String uuid, float x, float y, float size) {
		int px = Math.max(8, Math.round(c.px(size) / 8.0F) * 8);
		int ax = c.px(x + size / 2) - px / 2;
		int ay = c.px(y + size / 2) - px / 2;
		if (uuid == null) {
			Faces.own(c.frame(), ax, ay, px);
		} else {
			Faces.of(c.frame(), uuid, ax, ay, px);
		}
	}

	// ---------- wrapped text ----------

	/** Text in one of the menu's types, wrapped to a width; worked out again only when the text, width or face changes. */
	static final class Lines {
		private final Type type;
		private String text;
		private final List<TextLine> lines = new ArrayList<>();
		private @Nullable FontFace madeWith;
		private int wrappedFor = -1;

		Lines(Type type, String text) {
			this.type = type;
			this.text = text;
		}

		void set(String value) {
			if (!value.equals(text)) {
				text = value;
				wrappedFor = -1;
			}
		}

		String text() {
			return text;
		}

		/** The lines that fit {@code maxUnits}; a word wider than that is cut where it must be. */
		List<TextLine> lines(Canvas c, float maxUnits) {
			FontFace face = type.face();
			int max = Math.max(1, c.px(maxUnits));
			if (max != wrappedFor || face != madeWith) {
				wrappedFor = max;
				madeWith = face;
				lines.clear();
				TextLine probe = new TextLine(face, "");
				for (String block : text.split("\n", -1)) {
					StringBuilder current = new StringBuilder();
					for (String word : block.split(" ", -1)) {
						String candidate = current.isEmpty() ? word : current + " " + word;
						probe.set(candidate);
						if (c.width(probe) <= max) {
							current.setLength(0);
							current.append(candidate);
							continue;
						}
						if (!current.isEmpty()) {
							lines.add(new TextLine(face, current.toString()));
							current.setLength(0);
						}
						// A word wider than the line (a link, a long name) is cut letter by letter.
						String rest = word;
						probe.set(rest);
						while (!rest.isEmpty() && c.width(probe) > max) {
							int cut = fit(c, probe, rest, max);
							lines.add(new TextLine(face, rest.substring(0, cut)));
							rest = rest.substring(cut);
							probe.set(rest);
						}
						current.append(rest);
					}
					lines.add(new TextLine(face, current.toString()));
				}
			}
			return lines;
		}

		private static int fit(Canvas c, TextLine probe, String word, int max) {
			int cut = 1;
			for (int i = 1; i <= word.length(); i++) {
				if (Character.isLowSurrogate(word.charAt(i - 1)) && i > 1) {
					continue;
				}
				probe.set(word.substring(0, i));
				if (c.width(probe) > max) {
					break;
				}
				cut = i;
			}
			if (cut < word.length() && Character.isHighSurrogate(word.charAt(cut - 1)) && cut > 1) {
				cut--;
			}
			return Math.max(1, cut);
		}

		/** One line's height in units. */
		static float pitch(Canvas c) {
			return 12.0F * MenuStyle.pixelScale() / Math.max(1, c.scale());
		}

		float height(Canvas c, float maxUnits) {
			return lines(c, maxUnits).size() * pitch(c);
		}

		void paint(Canvas c, float x, float y, float maxUnits, int argb) {
			List<TextLine> shown = lines(c, maxUnits);
			float pitch = pitch(c);
			for (int i = 0; i < shown.size(); i++) {
				TextLine line = shown.get(i);
				c.text(line, c.px(x), c.baselineFor(line, c.px(y + pitch * i + pitch / 2)), argb);
			}
		}

		void paintCentered(Canvas c, float centerX, float y, float maxUnits, int argb) {
			List<TextLine> shown = lines(c, maxUnits);
			float pitch = pitch(c);
			for (int i = 0; i < shown.size(); i++) {
				TextLine line = shown.get(i);
				c.text(line, c.px(centerX) - c.width(line) / 2, c.baselineFor(line, c.px(y + pitch * i + pitch / 2)), argb);
			}
		}
	}

	// ---------- controls ----------

	/** A button in the window's look: secondary, accent or danger, its label (and glyph) centred; 40 design pixels tall. */
	static final class Button extends Pressable {
		static final float H = 40;
		private final Label label;
		private final @Nullable Glyph glyph;
		private PageControls.Look look;

		Button(String text, @Nullable Glyph glyph, PageControls.Look look, Runnable action) {
			super(action);
			this.label = new Label(Type.BUTTON, text);
			this.glyph = glyph;
			this.look = look;
			name(text);
		}

		void setText(String text) {
			if (!text.equals(label.text())) {
				label.set(text);
				name(text);
				invalidate();
			}
		}

		void setLook(PageControls.Look look) {
			this.look = look;
		}

		@Override
		public float prefWidth() {
			Canvas c = measure();
			return label.width(c) + 2 * d(16) + (glyph != null ? d(14) + d(8) : 0);
		}

		@Override
		public String narration() {
			return withReason(accessibleName() + ", button");
		}

		@Override
		protected void paint(Canvas c) {
			float rad = Math.min(MenuStyle.radius(), h / 2);
			int ink;
			if (!enabled()) {
				MenuStyle.box(c, x, y, w, h, rad, MenuStyle.SOFT);
				ink = Palette.withAlpha(MenuStyle.NEUTRAL, 0.6F);
			} else if (look == PageControls.Look.DANGER) {
				MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(Palette.lerp(MenuStyle.RED, 0xFFFFFFFF, 0.08F * hover), 0xFF000000, press * 0.15F));
				MenuStyle.halo(c, x, y, w, h, rad, hover, Palette.withAlpha(MenuStyle.RED, 0.30F));
				ink = 0xFFFFFFFF;
			} else {
				boolean accent = look == PageControls.Look.ACCENT;
				MenuStyle.button(c, x, y, w, h, accent, hover, press);
				ink = accent ? 0xFFFFFFFF : Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, 0.35F + 0.65F * hover);
			}
			float total = label.width(c) + (glyph != null ? d(14) + d(8) : 0);
			float left = x + Math.max(d(8), (w - total) / 2);
			if (glyph != null) {
				glyph.draw(c, left + d(7), y + h / 2, d(14), ink);
				left += d(14) + d(8);
			}
			MenuStyle.fitted(c, label, left, y + h / 2, ink, x + w - d(8) - left);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** A square button with one glyph (its name in a tooltip): the accent for the main answer (Accept), else secondary. */
	static final class Icon extends Pressable {
		private final Glyph glyph;
		private final boolean accent;

		Icon(Glyph glyph, String name, boolean accent, Runnable action) {
			super(action);
			this.glyph = glyph;
			this.accent = accent;
			name(name);
			tooltip(name);
		}

		@Override
		public String narration() {
			return withReason(accessibleName() + ", button");
		}

		@Override
		protected void paint(Canvas c) {
			float rad = Math.min(MenuStyle.radius(), h / 2);
			MenuStyle.button(c, x, y, w, h, accent, hover, press);
			int ink = accent ? 0xFFFFFFFF : Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, 0.35F + 0.65F * hover);
			glyph.draw(c, x + w / 2, y + h / 2, h * 0.42F, ink);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** A text field in the window's look (the search look with its glyph, or the plain field). */
	static final class Field extends TextField {
		private final boolean searchLook;
		private @Nullable FontFace face;

		Field(String placeholder, int maxLength, boolean searchLook) {
			super(placeholder, maxLength, searchLook);
			this.searchLook = searchLook;
		}

		@Override
		protected void layout() {
			FontFace now = Type.SEARCH.face();
			if (now != face) {
				face = now;
				look(new TextField.Look(now, MenuStyle.SEARCH, MenuStyle.SEARCH_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS, d(8), d(14),
					searchLook ? d(16) : 0, searchLook ? d(10) : 0, MenuStyle.SEARCH_TEXT, MenuStyle.SEARCH_HINT, MenuStyle.SEARCH_HINT));
			}
			super.layout();
		}
	}
}
