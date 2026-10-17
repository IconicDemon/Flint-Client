package com.flintclient.client.control.editor;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.menu.MenuCatalog;
import com.flintclient.client.control.model.Availability;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Column;
import com.flintclient.client.ui.kit.IconButton;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.ScrollView;
import com.flintclient.client.ui.kit.SearchField;
import com.flintclient.client.ui.kit.Switch;
import com.flintclient.client.ui.kit.Tokens2;
import com.flintclient.client.ui.kit.Type;

/**
 * The widget palette, docked at the right of the editor (WIDGETS on the hub or the toolbar): a card in the deck's colour
 * with a title, a close button, a search over the widgets' names, and one row per HUD widget the registry has built,
 * each with its icon, its name, why it shows nothing (when it doesn't), a gear for its settings and its switch. Clicking
 * a row, or Enter on it, puts the widget on (when it was off), selects it and closes the palette; the switch only turns
 * it on or off. Escape (in the search too) or a click on the surface closes it. Rows are made once, when the editor is
 * built; the search only shows and hides them.
 */
final class WidgetPalette extends Node {
	static final float WIDTH = 184;
	private static final float PAD = 6;
	private static final float HEADER = 20;
	private static final float ROW = 22;
	/** From the toolbar and from the screen's edges. */
	private static final float MARGIN = 6;

	private final Row[] rows = hudRows();
	private final TextLine title = new TextLine(Type.NAME, "Widgets");
	private final TextLine count = new TextLine(Type.META, "");
	private final TextLine empty = new TextLine(Type.BODY, "No widget matches that search");
	private final IconButton close;
	private final SearchField search;
	private final ScrollView scroll;
	private int shown;

	/** One row per module that's on the HUD and built (features like Zoom have no place on screen). */
	private static Row[] hudRows() {
		int n = 0;
		for (ModuleDescriptor module : ModuleRegistry.all()) {
			if (module.hud() && module.status().usable()) {
				n++;
			}
		}
		return new Row[n];
	}

	WidgetPalette(HudEditor editor) {
		close = add(new IconButton(Icon.CLOSE, "Close (Esc)", IconButton.TINY, editor::closePalette).iconSize(7));
		search = add(new SearchField("Search widgets", 40));
		search.onChange(this::filter);
		search.onEscape(editor::closePalette);
		search.name("Search widgets");
		Column list = new Column(0).padding(0, 2);
		int k = 0;
		for (int i = 0; i < ModuleRegistry.size(); i++) {
			ModuleDescriptor module = ModuleRegistry.all().get(i);
			if (module.hud() && module.status().usable()) {
				rows[k++] = list.add(new Row(editor, i));
			}
		}
		scroll = add(new ScrollView(list));
		shown = rows.length;
		count.set(Integer.toString(shown));
	}

	private void filter(String query) {
		shown = 0;
		for (Row row : rows) {
			boolean match = EditorMath.widgetMatches(query, row.descriptor.name(), row.descriptor.id());
			row.setVisible(match);
			if (match) {
				shown++;
			}
		}
		count.set(Integer.toString(shown));
		scroll.scrollToTop();
		invalidate();
	}

	/** Empties the search (the palette closed), so every widget shows when it opens again. */
	void clearSearch() {
		if (!search.text().isEmpty()) {
			search.setText("");
			filter("");
		}
	}

	/** Where keyboard focus goes when the palette opens: the search. */
	Node first() {
		return search;
	}

	/** Docked at the right, under the toolbar (above it, when the toolbar is at the bottom), kept on screen. */
	void place(float screenWidth, float screenHeight, boolean toolbarBottom) {
		float top = toolbarBottom ? MARGIN : EditorToolbar.TOP + EditorToolbar.HEIGHT + MARGIN;
		float bottom = toolbarBottom ? EditorToolbar.top(screenHeight, true) - MARGIN : screenHeight - MARGIN;
		float wanted = PAD + HEADER + SearchField.HEIGHT + PAD + rows.length * ROW + 4 + PAD;
		float h = Math.max(HEADER + 2 * ROW, Math.min(wanted, bottom - top));
		float w = Math.min(WIDTH, screenWidth - 2 * MARGIN);
		setBounds(screenWidth - MARGIN - w, top, w, h);
	}

	@Override
	protected void layout() {
		close.setBounds(x + w - PAD - IconButton.TINY, y + PAD + (HEADER - IconButton.TINY) / 2, IconButton.TINY, IconButton.TINY);
		search.setBounds(x + PAD, y + PAD + HEADER, w - 2 * PAD, SearchField.HEIGHT);
		float listTop = search.bottom() + PAD;
		scroll.setBounds(x + PAD - 2, listTop, w - 2 * PAD + 4, y + h - PAD - listTop);
	}

	@Override
	public boolean interactive() {
		// Presses on the palette's own padding stay in the palette.
		return true;
	}

	@Override
	public boolean scroll(float mx, float my, double amount) {
		// The wheel over the header scrolls the list, never the HUD under the palette.
		return scroll.scroll(mx, my, amount) || true;
	}

	@Override
	protected void paint(Canvas c) {
		EditorToolbar.paintCard(c, x, y, w, h, Tokens2.RADIUS_DECK);
		int center = c.px(y + PAD + HEADER / 2);
		int left = c.px(x + PAD + 2);
		c.text(title, left, c.baselineFor(title, center), Tokens2.TEXT);
		c.text(count, left + c.width(title) + c.px(5), c.baselineFor(count, center), Tokens2.muted());
		if (shown == 0) {
			int textW = c.width(empty);
			c.text(empty, c.px(x + w / 2) - textW / 2, c.baselineFor(empty, c.px(scroll.y() + 16)), Tokens2.muted(), c.px(w - 2 * PAD));
		}
	}

	/** One widget: its icon, name, why it shows nothing (when it doesn't), a gear for its settings and its switch. */
	private static final class Row extends Node {
		private static final float ICON = 9;
		/** The name's and the reason's centre lines in a two-line row, 4 units either side of its middle. */
		private static final float NAME_CENTRE = ROW / 2 - 4;
		private static final float REASON_CENTRE = ROW / 2 + 4;

		private final HudEditor editor;
		private final int index;
		final ModuleDescriptor descriptor;
		private final Icon icon;
		private final TextLine name;
		private final TextLine reason = new TextLine(Type.META, "");
		private final IconButton gear;
		private final Switch toggle;

		Row(HudEditor editor, int index) {
			this.editor = editor;
			this.index = index;
			this.descriptor = ModuleRegistry.all().get(index);
			this.icon = MenuCatalog.icon(descriptor.id());
			this.name = new TextLine(Type.LABEL, descriptor.name());
			this.gear = add(new IconButton(Icon.GEAR, descriptor.name() + " settings", IconButton.TINY, () -> editor.openSettings(index)).iconSize(8));
			this.toggle = add(new Switch(editor.module(index).enabled(), on -> editor.setEnabled(index, on)));
			toggle.name(descriptor.name() + " on the HUD");
			this.h = ROW;
		}

		@Override
		public float prefHeight(float width) {
			return ROW;
		}

		@Override
		protected void layout() {
			toggle.setBounds(x + w - Switch.TRACK_W - 8, y + (h - 16) / 2, Switch.TRACK_W + 4, 16);
			gear.setBounds(toggle.x() - IconButton.TINY - 2, y + (h - IconButton.TINY) / 2, IconButton.TINY, IconButton.TINY);
		}

		@Override
		public boolean interactive() {
			return true;
		}

		@Override
		public boolean focusable() {
			return true;
		}

		@Override
		public String narration() {
			boolean on = editor.module(index).enabled();
			String why = availability().reason();
			String text = descriptor.name() + (on ? ", on the HUD. Enter selects it" : ", off. Enter puts it on the HUD and selects it");
			return why == null ? text : text + ". " + why;
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			return button == Kit.LEFT_BUTTON;
		}

		@Override
		public void mouseUp(float mx, float my, int button) {
			if (button == Kit.LEFT_BUTTON && containsPoint(mx, my)) {
				activate();
			}
		}

		/** Puts the widget on (when off) and selects it; from the keyboard, focus moves to the editing surface. */
		@Override
		public void activate() {
			boolean keyboard = screen() != null && screen().focusVisible() && focused();
			Kit.clickSound();
			if (!editor.module(index).enabled()) {
				editor.setEnabled(index, true);
			} else {
				editor.select(index);
			}
			if (keyboard) {
				// Before closing, so focus goes straight to the surface (arrows then move the widget).
				editor.focusStage();
			}
			editor.closePalette();
		}

		private Availability availability() {
			return descriptor.availability(ControlState.inWorld(), ControlState.multiplayer(), ControlState.latencyKnown(),
				ControlState.networkCoreRunning());
		}

		@Override
		protected void paint(Canvas c) {
			boolean on = editor.module(index).enabled();
			if (toggle.on() != on) {
				toggle.setOn(on);
			}
			int x0 = px0(c);
			int y0 = py0(c);
			int pw = pxW(c);
			int ph = pxH(c);
			boolean chosen = editor.inGroup(index);
			float surface = Math.max(hover, chosen ? 1 : 0);
			if (surface > 0.01F) {
				c.roundRect(x0, y0, pw, ph, c.px(Tokens2.RADIUS_ROW), Palette.withAlpha(chosen ? Tokens2.SELECTED : Tokens2.HOVER, Math.max(surface, 0.01F)));
			}
			int iconPx = c.px(ICON);
			int tx = x0 + c.px(6);
			c.icon(icon, tx, y0 + (ph - iconPx) / 2, iconPx, on ? Tokens2.EMBER_TEXT : Tokens2.muted());
			int textLeft = tx + iconPx + c.px(7);
			int textRight = c.px(gear.x()) - c.px(4);
			String why = availability().reason();
			int ink = on ? Tokens2.TEXT : Tokens2.TEXT_DIM;
			if (why == null) {
				c.text(name, textLeft, c.baselineFor(name, y0 + ph / 2), ink, Math.max(0, textRight - textLeft));
			} else {
				reason.set(why);
				c.text(name, textLeft, c.baselineFor(name, y0 + c.px(NAME_CENTRE)), ink, Math.max(0, textRight - textLeft));
				int info = c.px(5);
				int infoY = y0 + c.px(REASON_CENTRE) - info / 2;
				c.icon(Icon.INFO, textLeft, infoY, info, Tokens2.WARN);
				int reasonLeft = textLeft + info + c.px(2.5F);
				c.text(reason, reasonLeft, c.baselineFor(reason, y0 + c.px(REASON_CENTRE)), Tokens2.WARN, Math.max(0, textRight - reasonLeft));
			}
			Kit.focusRing(c, x, y, w, h, Tokens2.RADIUS_ROW, focus);
		}
	}
}
