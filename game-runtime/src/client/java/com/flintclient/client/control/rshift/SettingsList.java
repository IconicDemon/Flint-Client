package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.PageControls.TextButton;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.ScrollView;
import com.flintclient.client.ui.kit.Wrap;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A settings page, as Dawn's (design pixels): a mod's (opened from its card) or a settings tab's. Its top row, 76 tall, has
 * the way back (mods only), the mod's icon, its name and what it does, and at the right its switch and Reset Settings (asks
 * first: Keep · Reset); under it a note when the page needs one, then the sections, 16 apart: each a title that folds it
 * away (a chevron in the accent) and a block of {@link Rows} in two columns, 24 inside it and 64 between the columns (one
 * column when the page is narrow). Only settings that work are listed, except on a page that is
 * a preview of what is coming; Flint's own extra sections ("More in Flint") start folded.
 */
final class SettingsList extends Node {
	/** The top row's height (24 above its 40-tall buttons, 12 below) and the page's padding. */
	private static final float HEADER = 76;
	private static final float PAD = 24;

	private final Label title;
	private final Label description;
	/** The mod whose page this is (its icon and switch in the header), or null for a settings tab. */
	private final Catalog.@Nullable Mod mod;
	private final ModsPage.@Nullable Status status;
	private float iconOn = -1;
	private final Parts.@Nullable Square back;
	private final TextButton reset;
	private final TextButton keep;
	private final TextButton confirm;
	private final Info info;
	private final Body body;
	private final ScrollView scroll;
	private final List<Catalog.Section> sections;
	private final Label question = new Label(Type.SENTENCE, "Reset every setting on this page?");
	/** Whether anything on the page can go back to a default (a page of rows Flint doesn't have can't). */
	private final boolean resettable;
	private boolean asking;

	SettingsList(Host host, String name, String about, @Nullable String note, List<Catalog.Section> sections, @Nullable Runnable onBack,
		@Nullable Node extra) {
		this(host, name, about, note, sections, onBack, extra, false);
	}

	/** {@code preview}: a page of what is coming (every row shown, none of them working). */
	SettingsList(Host host, String name, String about, @Nullable String note, List<Catalog.Section> sections, @Nullable Runnable onBack,
		@Nullable Node extra, boolean preview) {
		this(host, name, about, note, sections, onBack, extra, preview, null);
	}

	/** {@code mod}: the mod whose page this is, for its icon and its switch beside the name. */
	SettingsList(Host host, String name, String about, @Nullable String note, List<Catalog.Section> sections, @Nullable Runnable onBack,
		@Nullable Node extra, boolean preview, Catalog.@Nullable Mod mod) {
		this.sections = sections;
		this.mod = mod;
		title = new Label(Type.SENTENCE, name);
		description = new Label(Type.SENTENCE, about);
		back = onBack == null ? null : add(new Parts.Square(Glyph.CHEVRON_LEFT, "Back to the mods", onBack));
		resettable = sections.stream().anyMatch(s -> s.rows().stream().anyMatch(row -> row.inFlint() && !row.toggle()));
		reset = add(new TextButton("Reset Settings", Glyph.RESET, PageControls.Look.PLAIN, () -> setAsking(true)));
		keep = add(new TextButton("Keep", null, PageControls.Look.PLAIN, () -> setAsking(false)));
		confirm = add(new TextButton("Reset", null, PageControls.Look.DANGER, this::resetAll));
		confirm.name("Reset every " + name + " setting");
		status = mod != null && mod.module() != null && mod.module().status().usable() ? add(new ModsPage.Status(mod)) : null;
		info = new Info(note);
		body = new Body(host, info, sections, extra, name, preview);
		scroll = add(new ScrollView(body));
		setAsking(false);
	}

	private void setAsking(boolean value) {
		asking = value;
		reset.setVisible(!value && resettable);
		keep.setVisible(value);
		confirm.setVisible(value);
		if (status != null) {
			status.setVisible(!value);
		}
		invalidate();
	}

	/** Every setting on the page back to its default (module on/off rows are left as they are). */
	private void resetAll() {
		for (Catalog.Section s : sections) {
			for (Catalog.Row row : s.rows()) {
				if (row.inFlint() && !row.toggle()) {
					Rows.Access.of(row).reset();
				}
			}
		}
		setAsking(false);
	}

	/** Escape: a pending reset question goes first. */
	boolean escape() {
		if (asking) {
			setAsking(false);
			return true;
		}
		return false;
	}

	ScrollView scroll() {
		return scroll;
	}

	@Override
	protected void layout() {
		float right = x + w - d(PAD);
		float bh = d(40);
		float by = y + d(PAD);
		if (asking) {
			float cw = confirm.prefWidth();
			confirm.setBounds(right - cw, by, cw, bh);
			float kw = keep.prefWidth();
			keep.setBounds(right - cw - d(8) - kw, by, kw, bh);
		} else {
			float rw = reset.prefWidth();
			reset.setBounds(right - rw, by, rw, bh);
		}
		if (status != null) {
			float sw = d(140);
			float statusRight = reset.visible() ? reset.x() - d(8) : right;
			status.setBounds(statusRight - sw, by, sw, bh);
		}
		if (back != null) {
			back.setBounds(x + d(PAD), by, bh, bh);
		}
		scroll.flatBar(MenuStyle.SCROLL_THUMB, d(6), d(80));
		scroll.setBounds(x, y + d(HEADER), w, Math.max(0, h - d(HEADER)));
	}

	/** The top row: the mod's icon (white while on, grey while off) or the info mark, its name, then what it does in grey. */
	@Override
	protected void paint(Canvas c) {
		float cy = y + d(PAD) + d(20);
		float left = x + d(PAD) + (back != null ? d(40) + d(16) : 0);
		if (mod == null) {
			Glyph.INFO.draw(c, left + d(12), cy, d(20), MenuStyle.NEUTRAL);
		} else {
			iconOn = MenuStyle.ease(iconOn, status != null && status.on());
			ModsPage.drawModIcon(c, mod, left + d(12), cy, d(32), d(24), Palette.lerp(MenuStyle.ICON_OFF, MenuStyle.ICON_ON, iconOn));
		}
		left += d(24) + d(12);
		float end = status != null && status.visible() ? status.x() : asking ? keep.x() : reset.visible() ? reset.x() : x + w - d(PAD);
		if (asking) {
			float qw = question.width(c);
			MenuStyle.fitted(c, question, keep.x() - d(16) - qw, cy, MenuStyle.TEXT, qw + 1);
			end = keep.x() - d(16) - qw;
		}
		float room = end - d(24) - left;
		MenuStyle.fitted(c, title, left, cy, MenuStyle.TEXT, room);
		float after = left + Math.min(title.width(c), room) + d(16);
		if (after < end - d(24) - d(40)) {
			MenuStyle.fitted(c, description, after, cy, MenuStyle.NEUTRAL, end - d(24) - after);
		}
	}

	/** The note above the sections, wrapped to the page. */
	private static final class Info {
		private final @Nullable String text;
		private @Nullable Wrap wrap;

		Info(@Nullable String text) {
			this.text = text;
		}

		@Nullable Wrap wrap() {
			if (text == null) {
				return null;
			}
			FontFace face = Type.ROW_NOTE.face();
			if (wrap == null || wrap.face() != face) {
				wrap = new Wrap(face, text);
			}
			return wrap;
		}
	}

	/** The section titles shown: Flint's own extra sections lose their "More in Flint · " prefix. */
	static String shownTitle(String title) {
		if (title.equals(Catalog.MORE)) {
			return "More Settings";
		}
		return title.startsWith(Catalog.MORE + " · ") ? title.substring(Catalog.MORE.length() + 3) : title;
	}

	/**
	 * The scrolled part: the note, then each section (a title that folds it, and its rows in a card, in two columns when
	 * there is room), then the page's own panel (if any).
	 */
	private static final class Body extends Node {
		/** A section's title row, the space under it, and between sections; the list's own top. */
		private static final float TITLE = 22;
		private static final float UNDER_TITLE = 8;
		private static final float GAP = 16;
		private static final float TOP = 20;
		/** Below this width one column reads better than two. */
		private static final float TWO_COLUMNS = 760;
		/** The same, in pixels of Minecraft's font: two columns of rows need this many across. */
		private static final float TWO_COLUMNS_PER_FONT_PIXEL = 500;
		/** Around the rows inside a section's block. */
		private static final float CARD_PAD = 24;
		/** Between the two columns (32 either side of the middle), and between rows. */
		private static final float COLUMN_GAP = 64;
		private static final float ROW_GAP = 8;
		private final Info info;
		/**
		 * Each section's card from the last layout, from the list's top (a scroll moves the list without laying it out
		 * again); NaN when the section is folded.
		 */
		private float[] cardTop = new float[0];
		private float[] cardBottom = new float[0];
		private boolean two;
		private final List<Header> headers = new java.util.ArrayList<>();
		private final List<List<Node>> rows = new java.util.ArrayList<>();
		/**
		 * Each section's rows as built: a row, or the rows of the items the player added to a list (UHC Overlay's), which come
		 * and go with the list ({@link #syncAdded}).
		 */
		private final List<List<Object>> parts = new java.util.ArrayList<>();
		private final @Nullable Node extra;

		Body(Host host, Info info, List<Catalog.Section> sections, @Nullable Node extra, String page, boolean preview) {
			this.info = info;
			// What the mod does first; its toggle key (the Mod section) last.
			List<Catalog.Section> ordered = new java.util.ArrayList<>(sections);
			ordered.sort(java.util.Comparator.comparing(s -> s.title().equals("Mod")));
			for (Catalog.Section section : ordered) {
				List<Object> built = new java.util.ArrayList<>();
				for (Catalog.Row row : section.rows()) {
					if (preview || row.inFlint()) {
						// The items added to a list come as rows of their own, just above the row that adds them.
						Rows.AddedRows added = preview ? null : Rows.AddedRows.of(row);
						if (added != null) {
							built.add(added);
						}
						built.add(add(Rows.line(host, row)));
					}
				}
				List<Node> lines = lines(built, List.of());
				if (lines.isEmpty()) {
					continue;
				}
				boolean extraSection = section.title().startsWith(Catalog.MORE);
				headers.add(add(new Header(shownTitle(section.title()), page + "/" + section.title(), extraSection)));
				rows.add(lines);
				parts.add(built);
			}
			this.extra = extra == null ? null : add(extra);
		}

		/**
		 * A section's rows from its parts: each added item's row joins the page (hidden until it is laid out) and the rows of
		 * items no longer on the list leave it.
		 */
		private List<Node> lines(List<Object> built, List<Node> before) {
			List<Node> out = new java.util.ArrayList<>();
			for (Object part : built) {
				if (part instanceof Rows.AddedRows added) {
					for (Rows.Line line : added.lines()) {
						if (line.parent() != this) {
							line.setVisible(false);
							add(line);
						}
						out.add(line);
					}
				} else {
					out.add((Node) part);
				}
			}
			for (Node gone : before) {
				if (!out.contains(gone)) {
					remove(gone);
				}
			}
			return out;
		}

		/** Items added to or removed from a list since the last frame: their sections' rows again; whether any changed. */
		private boolean syncAdded() {
			boolean changed = false;
			for (int i = 0; i < parts.size(); i++) {
				boolean section = false;
				for (Object part : parts.get(i)) {
					if (part instanceof Rows.AddedRows added && added.changed()) {
						section = true;
					}
				}
				if (section) {
					rows.set(i, lines(parts.get(i), rows.get(i)));
					changed = true;
				}
			}
			return changed;
		}

		private boolean twoColumns(float width) {
			// Measured against the text as drawn: Minecraft's font keeps its pixel scale on a small window, so two columns
			// need the room for it (about 1000 window pixels at 2x), not just Dawn's design width.
			float inner = width - 2 * d(PAD) - 2 * d(CARD_PAD);
			return inner >= d(TWO_COLUMNS) && inner * measure().scale() / MenuStyle.pixelScale() >= TWO_COLUMNS_PER_FONT_PIXEL;
		}

		private float infoHeight(float width) {
			Wrap wrap = info.wrap();
			return wrap == null ? 0 : wrap.height(measure(), width - 2 * d(PAD) - 2 * d(CARD_PAD)) + 2 * d(16) + d(GAP);
		}

		private float columnWidth(float width, boolean twoColumns) {
			float inner = width - 2 * d(PAD) - 2 * d(CARD_PAD);
			return twoColumns ? (inner - d(COLUMN_GAP)) / 2 : inner;
		}

		/** A row that applies now: a setting whose switch is off waits out of sight. */
		private static boolean applies(Node row) {
			return !(row instanceof Rows.Line line) || line.applies();
		}

		private static List<Node> shown(List<Node> lines) {
			List<Node> out = new java.util.ArrayList<>(lines.size());
			for (Node row : lines) {
				if (applies(row)) {
					out.add(row);
				}
			}
			return out;
		}

		/** Which rows apply, as one number: when it changes, the page lays itself out again. */
		private long appliesKey() {
			long key = 17;
			for (List<Node> lines : rows) {
				for (Node row : lines) {
					key = key * 31 + (applies(row) ? 1 : 2);
				}
			}
			return key;
		}

		private long laidOutFor = Long.MIN_VALUE;

		/** The rows' heights in their columns: two side by side take the taller one's height. */
		private float rowsHeight(List<Node> all, float width) {
			List<Node> lines = shown(all);
			boolean pair = twoColumns(width);
			float colW = columnWidth(width, pair);
			float total = 0;
			for (int i = 0; i < lines.size(); i += pair ? 2 : 1) {
				float h = lines.get(i).prefHeight(colW);
				if (pair && i + 1 < lines.size()) {
					h = Math.max(h, lines.get(i + 1).prefHeight(colW));
				}
				total += (total > 0 ? d(ROW_GAP) : 0) + h;
			}
			return total;
		}

		@Override
		public float prefHeight(float width) {
			float total = d(TOP) + infoHeight(width);
			for (int i = 0; i < headers.size(); i++) {
				if (shown(rows.get(i)).isEmpty()) {
					continue;
				}
				total += d(TITLE);
				if (!headers.get(i).collapsed()) {
					total += d(UNDER_TITLE) + rowsHeight(rows.get(i), width) + 2 * d(CARD_PAD);
				}
				total += d(GAP);
			}
			return total + (extra != null ? extra.prefHeight(width - 2 * d(PAD)) + d(GAP) : 0) + d(PAD) - d(GAP);
		}

		@Override
		protected void layout() {
			float at = y + d(TOP) + infoHeight(w);
			two = twoColumns(w);
			float colW = columnWidth(w, two);
			float firstColumn = x + d(PAD) + d(CARD_PAD);
			if (cardTop.length != headers.size()) {
				cardTop = new float[headers.size()];
				cardBottom = new float[headers.size()];
			}
			laidOutFor = appliesKey();
			for (int i = 0; i < headers.size(); i++) {
				Header header = headers.get(i);
				List<Node> lines = shown(rows.get(i));
				for (Node row : rows.get(i)) {
					if (!lines.contains(row)) {
						row.setVisible(false);
					}
				}
				if (lines.isEmpty()) {
					// Nothing in it applies now: no title, no block.
					header.setVisible(false);
					cardTop[i] = Float.NaN;
					continue;
				}
				header.setVisible(true);
				header.setBounds(x + d(PAD), at, Math.min(w - 2 * d(PAD), header.prefWidth()), d(TITLE));
				at += d(TITLE);
				boolean shown = !header.collapsed();
				if (shown) {
					at += d(UNDER_TITLE);
				}
				cardTop[i] = shown ? at - y : Float.NaN;
				if (shown) {
					at += d(CARD_PAD);
				}
				boolean firstRow = true;
				for (int k = 0; k < lines.size(); k += two ? 2 : 1) {
					Node left = lines.get(k);
					Node right = two && k + 1 < lines.size() ? lines.get(k + 1) : null;
					float h = left.prefHeight(colW);
					if (right != null) {
						h = Math.max(h, right.prefHeight(colW));
					}
					if (shown && !firstRow) {
						at += d(ROW_GAP);
					}
					firstRow = false;
					left.setVisible(shown);
					left.setBounds(firstColumn, at, colW, h);
					if (right != null) {
						right.setVisible(shown);
						right.setBounds(firstColumn + colW + d(COLUMN_GAP), at, colW, h);
					}
					if (shown) {
						at += h;
					}
				}
				if (shown) {
					at += d(CARD_PAD);
				}
				cardBottom[i] = at - y;
				at += d(GAP);
			}
			if (extra != null) {
				extra.setBounds(x + d(PAD), at, w - 2 * d(PAD), extra.prefHeight(w - 2 * d(PAD)));
			}
		}

		@Override
		protected void paint(Canvas c) {
			if (syncAdded() | appliesKey() != laidOutFor) {
				// A switch changed what applies, or an item was added or removed: rows come and go.
				invalidate();
				if (parent() != null) {
					parent().invalidate();
				}
			}
			float at = y + d(TOP);
			Wrap wrap = info.wrap();
			if (wrap != null) {
				float ih = infoHeight(w) - d(GAP);
				MenuStyle.block(c, x + d(PAD), at, w - 2 * d(PAD), ih, MenuStyle.radius(), 0, 0);
				wrap.paint(c, x + d(PAD) + d(CARD_PAD), at + d(16), w - 2 * d(PAD) - 2 * d(CARD_PAD), MenuStyle.SENTENCE_TEXT);
			}
			for (int i = 0; i < headers.size() && i < cardTop.length; i++) {
				if (Float.isNaN(cardTop[i])) {
					continue;
				}
				float top = y + cardTop[i];
				float bottom = y + cardBottom[i];
				MenuStyle.block(c, x + d(PAD), top, w - 2 * d(PAD), bottom - top, MenuStyle.radius(), 0, 0);
			}
		}

		/** A section's title: pressing it folds the section away or opens it again (remembered between openings). */
		private final class Header extends com.flintclient.client.ui.kit.Pressable {
			private final Label title;
			private final String key;
			private final boolean foldedByDefault;
			private float open = -1;

			Header(String text, String key, boolean foldedByDefault) {
				super(null);
				this.title = new Label(Type.SECTION_TITLE, text);
				this.key = key;
				this.foldedByDefault = foldedByDefault;
				name(text + " section");
				setAction(() -> {
					com.flintclient.client.control.store.ControlSettings settings = com.flintclient.client.control.ControlState.settings();
					// A section folded by default remembers being opened (and the others being folded), so the list stays short.
					if (foldedByDefault) {
						settings.setSectionCollapsed("+" + key, collapsed());
					} else {
						settings.setSectionCollapsed(key, !collapsed());
					}
					Body.this.invalidate();
					if (Body.this.parent() != null) {
						Body.this.parent().invalidate();
					}
				});
			}

			boolean collapsed() {
				com.flintclient.client.control.store.ControlSettings settings = com.flintclient.client.control.ControlState.settings();
				return foldedByDefault ? !settings.sectionCollapsed("+" + key) : settings.sectionCollapsed(key);
			}

			@Override
			public String narration() {
				return title.text() + (collapsed() ? ", folded, open it" : ", open, fold it");
			}

			/** Dawn's title button: the 16 chevron, 12, then the title; only as wide as that. */
			@Override
			public float prefWidth() {
				return d(16) + d(12) + title.width(measure()) + d(4);
			}

			@Override
			protected void paint(Canvas c) {
				open = MenuStyle.ease(open, !collapsed());
				int ink = Palette.lerp(MenuStyle.TEXT, MenuStyle.ACCENT, hover);
				float cy = y + h / 2;
				// A chevron pointing down when open, right when folded.
				Glyph chevron = open >= 0.5F ? Glyph.CHEVRON_DOWN : Glyph.CHEVRON_RIGHT;
				chevron.draw(c, x + MenuStyle.d(8), cy, MenuStyle.d(14), MenuStyle.ACCENT);
				MenuStyle.fitted(c, title, x + d(16) + d(12), cy, ink, w - d(16) - d(12));
				MenuStyle.focusRing(c, x - d(4), y - d(2), w + d(8), h + d(4), d(4), focus);
			}
		}
	}
}
