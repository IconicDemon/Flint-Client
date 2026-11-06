package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.PageControls.TextButton;
import com.flintclient.client.platform.Ids;
import com.flintclient.client.platform.ItemPainter;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.SearchField;
import com.flintclient.client.ui.kit.TextField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A searchable list in a panel over the page, shared by every setting that picks one thing from many (a sound, an item)
 * and by lists of per-entry values (Better Sounds' volumes, UHC Overlay's items). Typing filters by name and id (every word
 * must match); only the rows in view are drawn, so lists of thousands cost the same as lists of ten. Items show their
 * picture. Rows can carry a preview button (play a sound) and, in value mode, a value with − and + and a reset, or, in a
 * list the player adds to, Add (and − and + and a remove once added). Esc, Done or a click outside closes it.
 */
final class ListPicker extends Node {
	/**
	 * One entry: its id (what is stored), what it's called, a quieter second line (the id, a category) and whether it is an
	 * item, drawn beside its name ({@code picture}: asked for as the row is drawn, since the game can't make one everywhere).
	 */
	record Item(String id, String label, String detail, boolean picture) {
		Item(String id, String label, String detail) {
			this(id, label, detail, false);
		}

		String haystack() {
			return (label + " " + detail + " " + id).toLowerCase(Locale.ROOT);
		}
	}

	/** Per-row values (value mode): read, change by a step, and whether a row differs from its default. */
	interface Values {
		int get(String id);

		void set(String id, int value);

		int min();

		int max();

		int step();

		int defaultValue();

		String unit();

		/** A value as the row shows it. */
		default String format(int value) {
			return value + unit();
		}

		/**
		 * The value Add gives an entry, in a list the player adds to (a row at the default shows Add; one added shows − and
		 * + and a remove); {@code Integer.MIN_VALUE} for a list where every row has − and +.
		 */
		default int addValue() {
			return Integer.MIN_VALUE;
		}
	}

	/** The width of the Add button, and of the space the item pictures take. */
	private static final float ADD_W = 150;
	private static final float ICON = 40;

	private static final float PANEL_W = 900;
	/** A row's height at least, and the most rows shown (fewer when the window is short). */
	private static final float ROW_H = 62;
	private static final int ROWS = 8;
	/**
	 * A row's height now: the menu's pixel font keeps its size while the layout scales, so on a small interface a row grows
	 * to hold its two lines (set by {@link #layout}).
	 */
	private float rowH = ROW_H;
	/** One line of the row font (its cap height with room below), and how many rows fit (set by {@link #layout}). */
	private float lineH;
	private int rows = ROWS;
	private static final float PAD = 26;
	private static final String NONE = "";

	private final Label title = new Label(Type.SECTION_TITLE, "");
	private final Label count = new Label(Type.ROW_NOTE, "");
	private final Label[] names = new Label[ROWS];
	private final Label[] details = new Label[ROWS];
	private final Label[] values = new Label[ROWS];
	private final SearchField search = add(new SearchField("Search", 60));
	private final TextButton changedOnly;
	private final TextButton done;
	private final List<Item> all = new ArrayList<>();
	private final List<Item> shown = new ArrayList<>();
	private final List<String> haystacks = new ArrayList<>();
	private @Nullable Supplier<String> current;
	private @Nullable Consumer<String> choose;
	private @Nullable Consumer<String> preview;
	private @Nullable Values valueModel;
	private boolean allowNone;
	private boolean onlyChanged;
	private int first;
	private int highlighted = -1;
	private @Nullable Node anchor;
	private @Nullable FontFace searchFace;
	private float px;
	private float py;
	private float ph;
	private float listY;

	ListPicker() {
		for (int i = 0; i < ROWS; i++) {
			names[i] = new Label(Type.ROW_LABEL, "");
			details[i] = new Label(Type.ROW_NOTE, "");
			values[i] = new Label(Type.VALUE, "");
		}
		changedOnly = add(new TextButton("Changed only", null, PageControls.Look.PLAIN, () -> {
			onlyChanged = !onlyChanged;
			refilter();
		}).active(() -> onlyChanged));
		done = add(new TextButton("Done", null, PageControls.Look.ACCENT, this::close));
		search.name("Search");
		search.onChange(text -> refilter());
		search.onSubmit(text -> pickHighlighted());
		search.onEscape(this::close);
		setVisible(false);
	}

	boolean open() {
		return visible();
	}

	/**
	 * Opens as a chooser: a click on a row (or None, when allowed) calls {@code choose} with its id ("" for None) and closes.
	 * {@code preview}, when given, adds a play button to each row.
	 */
	void choose(Node anchor, String name, List<Item> items, boolean allowNone, Supplier<String> current, Consumer<String> choose,
		@Nullable Consumer<String> preview) {
		start(anchor, name, items);
		this.allowNone = allowNone;
		this.current = current;
		this.choose = choose;
		this.preview = preview;
		this.valueModel = null;
		finish();
	}

	/** Opens as a list of values: each row's value changes in steps; rows that differ from the default can be listed alone. */
	void values(Node anchor, String name, List<Item> items, Values model, @Nullable Consumer<String> preview) {
		start(anchor, name, items);
		this.allowNone = false;
		this.current = null;
		this.choose = null;
		this.preview = preview;
		this.valueModel = model;
		finish();
	}

	private void start(Node anchor, String name, List<Item> items) {
		this.anchor = anchor;
		title.set(name);
		all.clear();
		all.addAll(items);
		haystacks.clear();
		for (Item item : items) {
			haystacks.add(item.haystack());
		}
		onlyChanged = false;
		search.setText("");
	}

	private void finish() {
		changedOnly.setVisible(valueModel != null);
		changedOnly.setText(adds(valueModel) ? "Added only" : "Changed only");
		refilter();
		// Start with the chosen entry in view.
		Supplier<String> now = current;
		if (now != null) {
			String id = now.get();
			for (int i = 0; i < shown.size(); i++) {
				if (shown.get(i).id().equals(id)) {
					first = clampFirst(i - rows / 2);
					highlighted = i;
					break;
				}
			}
		}
		setVisible(true);
		invalidate();
		KitScreen screen = screen();
		if (screen != null) {
			screen.focus(search);
		}
	}

	void close() {
		if (!visible()) {
			return;
		}
		setVisible(false);
		KitScreen screen = screen();
		Node back = anchor;
		if (screen != null) {
			screen.focus(back != null && screen.focusVisible() ? back : null);
		}
		anchor = null;
		current = null;
		choose = null;
		preview = null;
		valueModel = null;
	}

	/** The entries matching the search (every typed word somewhere in the name or id), None first when allowed. */
	private void refilter() {
		shown.clear();
		String[] words = search.text().toLowerCase(Locale.ROOT).trim().split("\\s+");
		boolean any = words.length == 1 && words[0].isEmpty();
		if (allowNone && any) {
			shown.add(new Item(NONE, "None", "No sound"));
		}
		Values model = valueModel;
		for (int i = 0; i < all.size(); i++) {
			if (!any && !matches(haystacks.get(i), words)) {
				continue;
			}
			Item item = all.get(i);
			if (onlyChanged && model != null && model.get(item.id()) == model.defaultValue()) {
				continue;
			}
			shown.add(item);
		}
		first = 0;
		highlighted = shown.isEmpty() ? -1 : 0;
		count.set(shown.size() == 1 ? "1 match" : String.format(Locale.ROOT, "%,d matches", shown.size()));
	}

	static boolean matches(String haystack, String[] words) {
		for (String word : words) {
			if (!word.isEmpty() && !haystack.contains(word)) {
				return false;
			}
		}
		return true;
	}

	private int clampFirst(int value) {
		return Math.max(0, Math.min(value, Math.max(0, shown.size() - rows)));
	}

	/** Enter in the search: chooses the highlighted row, or, in a list the player adds to, adds it. */
	private void pickHighlighted() {
		if (highlighted < 0 || highlighted >= shown.size()) {
			return;
		}
		Item item = shown.get(highlighted);
		Values model = valueModel;
		if (model != null && adds(model) && model.get(item.id()) == model.defaultValue()) {
			model.set(item.id(), model.addValue());
		} else if (choose != null) {
			pick(item);
		}
	}

	private void pick(Item item) {
		Consumer<String> target = choose;
		if (target != null) {
			target.accept(item.id());
			close();
		}
	}

	@Override
	public boolean interactive() {
		return visible();
	}

	// ---------- input ----------

	private float rowTop(int slot) {
		return listY + slot * rowH;
	}

	/** The row slot under (mx, my), or -1. */
	private int slotAt(float mx, float my) {
		if (mx < px + r(PAD) || mx > px + r(PANEL_W) - r(PAD) || my < listY) {
			return -1;
		}
		int slot = (int) Math.floor((my - listY) / rowH);
		return slot >= 0 && slot < rows && first + slot < shown.size() ? slot : -1;
	}

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		if (!(mx >= px && mx < px + r(PANEL_W) && my >= py && my < py + ph)) {
			close();
			return true;
		}
		int slot = slotAt(mx, my);
		if (slot < 0) {
			return true;
		}
		Item item = shown.get(first + slot);
		highlighted = first + slot;
		float right = px + r(PANEL_W) - r(PAD);
		float cy = rowTop(slot) + rowH / 2;
		float size = r(44);
		// Buttons from the right: [reset] [+] value [−] [play].
		Values model = valueModel;
		if (model != null && toggles(model)) {
			model.set(item.id(), model.get(item.id()) == 0 ? 1 : 0);
			return true;
		}
		if (model != null && adds(model) && model.get(item.id()) == model.defaultValue()) {
			// Not added yet: the Add button (or anywhere on the row) adds it.
			model.set(item.id(), model.addValue());
			return true;
		}
		if (model != null) {
			if (hit(mx, my, right - size, cy, size)) {
				model.set(item.id(), model.defaultValue());
				return true;
			}
			float plus = right - size - r(10) - size;
			if (hit(mx, my, plus, cy, size)) {
				model.set(item.id(), Math.min(model.max(), model.get(item.id()) + model.step()));
				return true;
			}
			float minus = plus - r(110) - size;
			if (hit(mx, my, minus, cy, size)) {
				model.set(item.id(), Math.max(model.min(), model.get(item.id()) - model.step()));
				return true;
			}
			if (preview != null && hit(mx, my, minus - r(16) - size, cy, size)) {
				preview.accept(item.id());
			}
			return true;
		}
		if (preview != null && !item.id().isEmpty() && hit(mx, my, right - size, cy, size)) {
			preview.accept(item.id());
			return true;
		}
		pick(item);
		return true;
	}

	/** A value list of 0 and 1 without a unit is a check list: a click on a row ticks it or clears it. */
	private static boolean toggles(Values model) {
		return model.min() == 0 && model.max() == 1 && model.unit().isEmpty();
	}

	/** A list the player adds entries to (UHC Overlay's items). */
	private static boolean adds(@Nullable Values model) {
		return model != null && model.addValue() != Integer.MIN_VALUE;
	}

	private static boolean hit(float mx, float my, float bx, float cy, float size) {
		return mx >= bx && mx < bx + size && my >= cy - size / 2 && my < cy + size / 2;
	}

	@Override
	public boolean scroll(float mx, float my, double amount) {
		first = clampFirst(first - (int) Math.signum(amount) * Math.max(1, (int) Math.round(Math.abs(amount) * 2)));
		return true;
	}

	@Override
	public boolean keyDown(KeyInput event) {
		if (event.isEscape()) {
			close();
			return true;
		}
		if (event.isDown() || event.isUp()) {
			if (!shown.isEmpty()) {
				highlighted = Math.max(0, Math.min(shown.size() - 1, highlighted + (event.isDown() ? 1 : -1)));
				if (highlighted < first) {
					first = highlighted;
				} else if (highlighted >= first + rows) {
					first = clampFirst(highlighted - rows + 1);
				}
			}
			return true;
		}
		return super.keyDown(event);
	}

	// ---------- layout and drawing ----------

	@Override
	protected void layout() {
		Node from = anchor;
		float pw = r(PANEL_W);
		// Two lines of the pixel font with room around them, never less than the design's row; as many rows as fit.
		Canvas m = measure();
		lineH = Type.ROW_LABEL.face().capHeightPx(m.scale()) / (float) m.scale() * 1.5F;
		rowH = Math.max(r(ROW_H), 2 * lineH + r(20));
		float fixed = r(PAD) + r(40) + r(20) + r(58) + r(16) + r(20) + r(64) + r(PAD);
		rows = Math.clamp((int) Math.floor((h - r(32) - fixed) / rowH), 3, ROWS);
		ph = Math.min(fixed + rows * rowH, h - r(32));
		float ax = from != null ? from.x() + from.width() - pw : x + (w - pw) / 2;
		px = Math.clamp(ax, x + r(16), Math.max(x + r(16), x + w - pw - r(16)));
		py = Math.clamp(from != null ? from.y() - ph / 3 : y + (h - ph) / 2, y + r(16), Math.max(y + r(16), y + h - ph - r(16)));
		float inner = px + r(PAD);
		float top = py + r(PAD) + r(40) + r(20);
		float changedW = changedOnly.visible() ? changedOnly.prefWidth() : 0;
		float fieldRight = px + pw - r(PAD) - (changedW > 0 ? changedW + r(12) : 0);
		search.setBounds(inner, top, fieldRight - inner, r(58));
		if (changedOnly.visible()) {
			changedOnly.setBounds(px + pw - r(PAD) - changedW, top, changedW, r(58));
		}
		listY = top + r(58) + r(16);
		float doneW = done.prefWidth();
		done.setBounds(px + pw - r(PAD) - doneW, py + ph - r(PAD) - r(64), doneW, r(64));
		FontFace face = Type.SEARCH.face();
		if (face != searchFace) {
			searchFace = face;
			search.look(new TextField.Look(face, MenuStyle.SEARCH, MenuStyle.SEARCH_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS, r(15),
				r(24), r(30), r(18), MenuStyle.SEARCH_TEXT, MenuStyle.SEARCH_HINT, MenuStyle.SEARCH_HINT));
		}
	}

	@Override
	protected void paint(Canvas c) {
		float pw = r(PANEL_W);
		int x0 = c.px(px);
		int y0 = c.px(py);
		int w0 = c.px(px + pw) - x0;
		int h0 = c.px(py + ph) - y0;
		for (int i = 3; i >= 1; i--) {
			int spread = c.px(r(8 * i));
			c.roundRect(x0 - spread, y0 - spread + c.px(r(10)), w0 + 2 * spread, h0 + 2 * spread, c.px(r(22)) + spread,
				Palette.withAlpha(0xFF000000, 0.10F));
		}
		MenuStyle.block(c, px, py, pw, ph, MenuStyle.radius(), 0, 0);
		MenuStyle.text(c, title, px + r(PAD), py + r(PAD) + r(20), MenuStyle.ROW_TEXT);
		MenuStyle.fitted(c, count, px + r(PAD), done.y() + done.height() / 2, MenuStyle.LABEL, done.x() - px - r(PAD) * 2);
		KitScreen screen = screen();
		int hover = screen != null ? slotAt(screen.mouseX(), screen.mouseY()) : -1;
		String chosen = current != null ? current.get() : null;
		Values model = valueModel;
		float inner = px + r(PAD);
		float right = px + pw - r(PAD);
		float button = r(44);
		if (shown.isEmpty()) {
			names[0].set("Nothing matches");
			MenuStyle.text(c, names[0], inner + r(16), listY + rowH / 2, MenuStyle.LABEL);
		}
		for (int slot = 0; slot < rows && first + slot < shown.size(); slot++) {
			int index = first + slot;
			Item item = shown.get(index);
			float top = rowTop(slot);
			float cy = top + rowH / 2;
			boolean selected = chosen != null && chosen.equals(item.id());
			if (slot == hover || index == highlighted) {
				MenuStyle.box(c, inner, top + r(3), right - inner, rowH - r(6), r(12), slot == hover ? MenuStyle.CHIP_HOVER : MenuStyle.CHIP);
			}
			float textX = inner + r(16);
			if (selected) {
				Glyph.CHECK.draw(c, inner + r(22), cy, r(24), MenuStyle.ACCENT);
				textX = inner + r(46);
			}
			// An item's picture, as the game draws it in an inventory: a whole multiple of its 16 pixels, so it stays crisp. Its
			// place is kept even while the game can't draw items yet (the title screen on 26.x), so the names never shift.
			if (item.picture()) {
				int size = Math.max(16, Math.round(c.px(r(ICON)) / 16F) * 16);
				Object stack = Ids.stack(item.id());
				if (stack != null) {
					ItemPainter.item(c.frame(), stack, c.px(textX), c.px(cy) - size / 2, size, null, false);
				}
				textX += Math.max(r(ICON), size / (float) c.scale()) + r(14);
			}
			boolean adding = model != null && adds(model) && model.get(item.id()) == model.defaultValue();
			float textRight = right - (adding ? r(ADD_W) + r(16)
				: model != null && !toggles(model) ? r(330) : model != null ? button + r(16) : preview != null ? button + r(16) : 0);
			names[slot].set(item.label());
			details[slot].set(item.detail());
			boolean twoLines = !item.detail().isEmpty();
			// The two lines a line apart whatever the font's size against the layout's.
			float half = Math.max(r(13), lineH * 0.55F);
			MenuStyle.fitted(c, names[slot], textX, twoLines ? cy - half : cy, MenuStyle.ROW_TEXT, Math.max(r(40), textRight - textX));
			if (twoLines) {
				MenuStyle.fitted(c, details[slot], textX, cy + half, MenuStyle.LABEL, Math.max(r(40), textRight - textX));
			}
			if (model != null && toggles(model)) {
				boolean on = model.get(item.id()) == 1;
				float bx = right - button;
				MenuStyle.box(c, bx, cy - button / 2, button, button, r(10), on ? MenuStyle.ACCENT : MenuStyle.CHIP);
				if (on) {
					Glyph.CHECK.draw(c, bx + button / 2, cy, r(26), 0xFFFFFFFF);
				}
			} else if (adding) {
				// Not in the list yet: an Add button in ember outline.
				float bx = right - r(ADD_W);
				boolean hot = slot == hover;
				MenuStyle.box(c, bx, cy - button / 2, r(ADD_W), button, r(10), hot ? MenuStyle.ACCENT : MenuStyle.CHIP);
				MenuStyle.ring(c, bx, cy - button / 2, r(ADD_W), button, r(10), MenuStyle.edge(), Palette.withAlpha(MenuStyle.ACCENT, hot ? 0 : 0.6F));
				int ink = hot ? 0xFFFFFFFF : MenuStyle.ACCENT;
				Glyph.PLUS.draw(c, bx + r(30), cy, r(18), ink);
				values[slot].set("Add");
				MenuStyle.centered(c, values[slot], bx + r(ADD_W) / 2 + r(12), cy, ink, r(ADD_W) - r(50));
			} else if (model != null) {
				int value = model.get(item.id());
				float reset = right - button;
				float plus = reset - r(10) - button;
				float minus = plus - r(110) - button;
				// In a list the player adds to, the last button takes the entry out of it.
				chip(c, reset, cy, button, adds(model) ? Glyph.TRASH : Glyph.RESET, value != model.defaultValue());
				chip(c, plus, cy, button, Glyph.PLUS, value < model.max());
				chip(c, minus, cy, button, Glyph.MINUS, value > model.min());
				values[slot].set(model.format(value));
				MenuStyle.centered(c, values[slot], minus + button + r(55), cy, value != model.defaultValue() ? MenuStyle.ACCENT : MenuStyle.ROW_TEXT,
					r(110));
				if (preview != null) {
					chip(c, minus - r(16) - button, cy, button, Glyph.PLAY, true);
				}
			} else if (preview != null && !item.id().isEmpty()) {
				chip(c, right - button, cy, button, Glyph.PLAY, true);
			}
		}
		// A thin bar showing where the rows are in a long list.
		if (shown.size() > rows) {
			float trackH = rows * rowH;
			float thumbH = Math.max(r(30), trackH * rows / shown.size());
			float thumbY = listY + (trackH - thumbH) * first / Math.max(1, shown.size() - rows);
			MenuStyle.box(c, right + r(8), thumbY, r(6), thumbH, r(3), MenuStyle.SCROLL_THUMB);
		}
	}

	private static void chip(Canvas c, float bx, float cy, float size, Glyph icon, boolean on) {
		MenuStyle.box(c, bx, cy - size / 2, size, size, r(10), MenuStyle.CHIP);
		icon.draw(c, bx + size / 2, cy, r(22), on ? MenuStyle.CHIP_TEXT : Palette.withAlpha(MenuStyle.CHIP_TEXT, 0.35F));
	}
}
