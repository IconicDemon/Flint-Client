package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.Hsv;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.PageControls.TextButton;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.TextField;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The colour picker: a panel under the swatch that opened it, over the page (not dimming it, so the preview shows each
 * colour as it is picked). Any colour, as Dawn's picker picks it: a square of saturation (across) and brightness (down)
 * in the hue the bar beside it sets, both dragged or moved with the arrow keys. Beside them, the recent colours (the
 * last eight used, then Flint's basics), Transparent for a colour that can be see-through (every background and fill
 * can), and a hex field (a valid {@code #RRGGBB} or {@code #RGB} applies as it is typed; anything else is marked and
 * never applied). Default (the setting's own colour) and Done close the row. Esc, Done or a click outside the panel
 * closes it, and a colour that changed becomes the first recent one. It covers the whole window while open so that
 * outside click lands here.
 */
final class ColorPicker extends Node {
	/** Shown after the recent colours until eight have been used: white, black, Flint orange and the rainbow's five. */
	private static final int[] BASICS = {0xFFFFFF, 0x000000, 0xFF8139, 0xFF5A5A, 0xFFE36E, 0x4CC27A, 0x5B8CFF, 0xA88BFF};
	private static final String[] BASIC_NAMES = {"White", "Black", "Flint orange", "Red", "Yellow", "Green", "Blue", "Violet"};

	/**
	 * How a colour becomes see-through: {@code on} while it is (its opacity at 0), {@code clear} makes it so, and
	 * {@code restore} gives it back an opacity when a colour is picked again.
	 */
	record Clear(java.util.function.BooleanSupplier on, Runnable clear, Runnable restore) {
	}

	/** Wide enough for Transparent, Default and Done on one row in the menu's pixel font at its smallest layout. */
	private static final float PANEL_W = 760;
	/** The tallest a recent colour's cell gets. */
	private static final float CELL_H = 52;
	private static final float PAD = 26;
	/** The saturation and brightness square, and the hue bar beside it. */
	private static final float SQUARE = 300;
	private static final float BAR_W = 34;
	private static final float BAR_GAP = 16;
	private static final float COLUMN_GAP = 26;
	private static final float CELL_GAP = 10;
	private static final float FIELD_H = 56;
	private static final float BUTTON_H = 64;
	/** The hue bar's stops, top to bottom: red, yellow, green, cyan, blue, magenta and red again. */
	private static final int[] HUES = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};

	private final Label title = new Label(Type.SECTION_TITLE, "");
	private final Label hexNote = new Label(Type.ROW_NOTE, "");
	private final Label recentLabel = new Label(Type.ROW_NOTE, "Recent");
	private final Label hexLabel = new Label(Type.ROW_NOTE, "Hex");
	private final SquareArea square = add(new SquareArea());
	private final HueBar bar = add(new HueBar());
	private final Swatches swatches = add(new Swatches());
	private final TransparentChip transparent = add(new TransparentChip());
	private final TextField hex = add(new TextField("#RRGGBB", 7));
	private final TextButton reset;
	private final TextButton done;
	private @Nullable IntSupplier get;
	private @Nullable IntConsumer set;
	private @Nullable Clear clear;
	private int defaultRgb;
	/** The colour when the picker opened, so closing knows whether one was picked. */
	private int openedRgb;
	private boolean openedClear;
	private @Nullable Node anchor;
	private @Nullable FontFace hexFace;
	/** The colour as the square and bar hold it: kept apart from the setting so a grey or black keeps its hue. */
	private float hue;
	private float saturation;
	private float value;
	/** The setting's colour the three were last read from (so a change from elsewhere, Default or a typed hex, is seen). */
	private int hsvFor = -1;
	/** The recent colours shown, read when the picker opens. */
	private int[] recent = new int[0];
	/** One line of the labels' font, in layout units (set by {@link #layout}). */
	private float labelH;
	private float px;
	private float py;
	private float ph;

	ColorPicker() {
		reset = add(new TextButton("Default", null, PageControls.Look.PLAIN, () -> apply(defaultRgb)));
		done = add(new TextButton("Done", null, PageControls.Look.ACCENT, this::close));
		hex.name("Hex color");
		hex.onChange(this::typed);
		hex.onSubmit(text -> close());
		hex.onEscape(this::close);
		setVisible(false);
	}

	boolean open() {
		return visible();
	}

	/** Opens for a colour called {@code name}, under {@code anchor}. */
	void show(Node anchor, String name, int defaultRgb, IntSupplier get, IntConsumer set, @Nullable Clear clear) {
		this.anchor = anchor;
		this.get = get;
		this.set = set;
		this.clear = clear;
		this.defaultRgb = defaultRgb;
		openedRgb = get.getAsInt() & 0xFFFFFF;
		openedClear = isClear();
		title.set(name);
		hex.setText(isClear() ? "" : RgbSetting.hex(openedRgb));
		readHsv(openedRgb);
		recent = recentColors();
		transparent.setVisible(clear != null);
		setVisible(true);
		invalidate();
		KitScreen screen = screen();
		if (screen != null) {
			screen.focus(hex);
		}
	}

	void close() {
		if (!visible()) {
			return;
		}
		// A colour picked (not just looked at) is the first recent one next time.
		if (get != null && !isClear() && (openedClear || current() != openedRgb)) {
			ControlState.settings().addRecentColor(current());
		}
		setVisible(false);
		KitScreen screen = screen();
		Node back = anchor;
		if (screen != null) {
			screen.focus(back != null && screen.focusVisible() ? back : null);
		}
		anchor = null;
		get = null;
		set = null;
		clear = null;
	}

	/** The recent colours, then Flint's basics not among them, eight in all. */
	private static int[] recentColors() {
		int[] saved = ControlState.settings().recentColors();
		int[] out = new int[BASICS.length];
		int n = 0;
		for (int rgb : saved) {
			if (n < out.length) {
				out[n++] = rgb;
			}
		}
		for (int basic : BASICS) {
			if (n >= out.length) {
				break;
			}
			boolean seen = false;
			for (int i = 0; i < n; i++) {
				seen |= out[i] == basic;
			}
			if (!seen) {
				out[n++] = basic;
			}
		}
		return out;
	}

	private static String colorName(int rgb) {
		for (int i = 0; i < BASICS.length; i++) {
			if (BASICS[i] == rgb) {
				return BASIC_NAMES[i];
			}
		}
		return RgbSetting.hex(rgb);
	}

	private boolean isClear() {
		Clear c = clear;
		return c != null && c.on().getAsBoolean();
	}

	/** A colour is picked or typed: a see-through one gets its opacity back first. */
	private void opaque() {
		Clear c = clear;
		if (c != null && c.on().getAsBoolean()) {
			c.restore().run();
		}
	}

	/** Reads the square's and bar's places from {@code rgb}, keeping the hue for a grey. */
	private void readHsv(int rgb) {
		float[] hsv = Hsv.of(rgb, hue);
		hue = hsv[0];
		saturation = hsv[1];
		value = hsv[2];
		hsvFor = rgb;
	}

	/** The square or the bar moved: the colour they now say, applied (without reading the places back from it). */
	private void fromHsv() {
		int rgb = Hsv.rgb(hue, saturation, value);
		IntConsumer target = set;
		if (target != null) {
			opaque();
			target.accept(rgb);
			hsvFor = rgb;
			hex.setText(RgbSetting.hex(rgb));
		}
	}

	private void apply(int rgb) {
		IntConsumer target = set;
		if (target != null) {
			opaque();
			target.accept(rgb & 0xFFFFFF);
			hex.setText(RgbSetting.hex(rgb));
			readHsv(rgb & 0xFFFFFF);
		}
	}

	private void typed(String text) {
		int rgb = RgbSetting.parseHex(text);
		IntConsumer target = set;
		if (rgb >= 0 && target != null && (rgb != current() || isClear())) {
			opaque();
			target.accept(rgb);
			readHsv(rgb);
		}
	}

	private int current() {
		IntSupplier source = get;
		return source != null ? source.getAsInt() & 0xFFFFFF : 0;
	}

	@Override
	public boolean interactive() {
		return visible();
	}

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		// A click outside the panel closes it (and goes no further).
		if (!(mx >= px && mx < px + r(PANEL_W) && my >= py && my < py + ph)) {
			close();
		}
		return true;
	}

	@Override
	public boolean keyDown(KeyInput event) {
		if (event.isEscape()) {
			close();
			return true;
		}
		return super.keyDown(event);
	}

	@Override
	protected void layout() {
		Node from = anchor;
		float pw = r(PANEL_W);
		ph = r(PAD) + r(40) + r(18) + r(SQUARE) + r(24) + r(BUTTON_H) + r(PAD);
		float ax = from != null ? from.x() + from.width() - pw : x + (w - pw) / 2;
		float below = from != null ? from.y() + from.height() + r(12) : y + (h - ph) / 2;
		float above = from != null ? from.y() - r(12) - ph : below;
		px = Math.clamp(ax, x + r(16), Math.max(x + r(16), x + w - pw - r(16)));
		py = below + ph <= y + h - r(16) ? below : Math.max(y + r(16), above);
		float inner = px + r(PAD);
		float top = py + r(PAD) + r(40) + r(18);
		square.setBounds(inner, top, r(SQUARE), r(SQUARE));
		bar.setBounds(square.right() + r(BAR_GAP), top, r(BAR_W), r(SQUARE));
		// The menu's pixel font keeps its size while the layout scales: a label's room follows the font, not the layout.
		Canvas m = measure();
		labelH = Type.ROW_NOTE.face().capHeightPx(m.scale()) / (float) m.scale() * 1.5F;
		float labelRoom = Math.max(r(28), labelH + r(12));
		// The column beside them: the recent colours (two rows of four) under their label, the hex field at the bottom.
		float colX = bar.right() + r(COLUMN_GAP);
		float colW = px + pw - r(PAD) - colX;
		float cellH = Math.min((colW - 3 * r(CELL_GAP)) / 4, r(CELL_H));
		swatches.setBounds(colX, top + labelRoom, colW, cellH * 2 + r(CELL_GAP));
		hex.setBounds(colX, top + r(SQUARE) - r(FIELD_H), colW, r(FIELD_H));
		float rowY = top + r(SQUARE) + r(24);
		float doneW = done.prefWidth();
		float resetW = reset.prefWidth();
		done.setBounds(px + pw - r(PAD) - doneW, rowY, doneW, r(BUTTON_H));
		reset.setBounds(done.x() - r(12) - resetW, rowY, resetW, r(BUTTON_H));
		// Transparent (for a colour that can be see-through) starts the row of buttons, as wide as its word needs.
		transparent.setBounds(inner, rowY, Math.min(transparent.prefWidth(), reset.x() - r(12) - inner), r(BUTTON_H));
		FontFace face = Type.VALUE.face();
		if (face != hexFace) {
			hexFace = face;
			hex.look(new TextField.Look(face, MenuStyle.FIELD, MenuStyle.FIELD_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS, r(12),
				r(18), 0, 0, MenuStyle.ROW_TEXT, MenuStyle.LABEL, MenuStyle.LABEL));
		}
	}

	@Override
	protected void paint(Canvas c) {
		// A colour set from elsewhere (Default, a typed hex) moves the thumbs to it.
		int now = current();
		if (!isClear() && now != hsvFor) {
			readHsv(now);
		}
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
		// The colour now, beside the title.
		float chip = r(40);
		if (isClear()) {
			MenuStyle.transparent(c, px + pw - r(PAD) - chip, py + r(PAD), chip, chip, r(10));
		} else {
			MenuStyle.box(c, px + pw - r(PAD) - chip, py + r(PAD), chip, chip, r(10), 0xFF000000 | now);
		}
		MenuStyle.ring(c, px + pw - r(PAD) - chip, py + r(PAD), chip, chip, r(10), MenuStyle.edge(), 0x40FFFFFF);
		float labelLift = Math.max(r(16), labelH * 0.5F + r(6));
		MenuStyle.text(c, recentLabel, swatches.x(), swatches.y() - labelLift, MenuStyle.LABEL);
		MenuStyle.text(c, hexLabel, hex.x(), hex.y() - labelLift, MenuStyle.LABEL);
		// A hex that isn't a colour is marked, and never applied.
		boolean invalid = !hex.text().isBlank() && RgbSetting.parseHex(hex.text()) < 0;
		if (invalid) {
			hexNote.set("Use #RRGGBB, like #FF8139");
			MenuStyle.ring(c, hex.x(), hex.y(), hex.width(), hex.height(), r(12), Math.max(1, Math.round(MenuStyle.edge() * 1.5F)),
				MenuStyle.DANGER);
			MenuStyle.fitted(c, hexNote, hex.x() + r(4), hex.y() + hex.height() + r(14), MenuStyle.DANGER_TEXT, hex.width());
		}
	}

	/** A round thumb: the colour it stands on, a white ring and a soft dark one outside it, larger while held. */
	private static void thumb(Canvas c, float cx, float cy, int rgb, float held) {
		float size = r(22) + r(6) * held;
		float x = cx - size / 2;
		float y = cy - size / 2;
		MenuStyle.box(c, x - r(3), y - r(3), size + r(6), size + r(6), size / 2 + r(3), 0x66000000);
		MenuStyle.box(c, x, y, size, size, size / 2, 0xFFFFFFFF);
		float in = r(4);
		MenuStyle.box(c, x + in, y + in, size - 2 * in, size - 2 * in, size / 2 - in, 0xFF000000 | rgb);
	}

	/** The square: saturation from left to right, brightness from top to bottom, in the bar's hue. */
	private final class SquareArea extends Pressable {
		private boolean dragging;

		SquareArea() {
			super(null);
			name("Saturation and brightness");
		}

		private void setFrom(float mx, float my) {
			saturation = Math.clamp((mx - x) / Math.max(1, w), 0, 1);
			value = 1 - Math.clamp((my - y) / Math.max(1, h), 0, 1);
			fromHsv();
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			if (button == Kit.LEFT_BUTTON) {
				dragging = true;
				setFrom(mx, my);
			}
			return super.mouseDown(mx, my, button);
		}

		@Override
		public void mouseDrag(float mx, float my) {
			if (dragging) {
				setFrom(mx, my);
			}
		}

		@Override
		public void mouseUp(float mx, float my, int button) {
			dragging = false;
			super.mouseUp(mx, my, button);
		}

		@Override
		public boolean keyDown(KeyInput event) {
			float by = event.hasShiftDown() ? 0.1F : 0.02F;
			if (event.isLeft() || event.isRight()) {
				saturation = Math.clamp(saturation + (event.isLeft() ? -by : by), 0, 1);
				fromHsv();
				return true;
			}
			if (event.isUp() || event.isDown()) {
				value = Math.clamp(value + (event.isDown() ? -by : by), 0, 1);
				fromHsv();
				return true;
			}
			return super.keyDown(event);
		}

		@Override
		public String narration() {
			return "Saturation " + Math.round(saturation * 100) + " percent, brightness " + Math.round(value * 100)
				+ " percent. Arrow keys to change";
		}

		@Override
		protected void paint(Canvas c) {
			int x0 = c.px(x);
			int y0 = c.px(y);
			int pw = c.px(x + w) - x0;
			int ph = c.px(y + h) - y0;
			int radius = c.px(r(12));
			// White to the pure hue across, then clear to black down over it.
			c.roundLinear(x0, y0, pw, ph, radius, 90, new int[] {0xFFFFFFFF, 0xFF000000 | Hsv.rgb(hue, 1, 1)}, 1);
			c.roundGradient(x0, y0, pw, ph, radius, 0x00000000, 0xFF000000);
			MenuStyle.ring(c, x, y, w, h, r(12), MenuStyle.edge(), 0x30FFFFFF);
			thumb(c, x + saturation * w, y + (1 - value) * h, Hsv.rgb(hue, saturation, value), Math.max(press, dragging ? 1 : 0));
			MenuStyle.focusRing(c, x, y, w, h, r(12), focus);
		}
	}

	/** The hue bar: the colour wheel's hues from top to bottom. */
	private final class HueBar extends Pressable {
		private boolean dragging;

		HueBar() {
			super(null);
			name("Hue");
		}

		private void setFrom(float my) {
			hue = Math.clamp((my - y) / Math.max(1, h), 0, 1) * 359.99F;
			fromHsv();
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			if (button == Kit.LEFT_BUTTON) {
				dragging = true;
				setFrom(my);
			}
			return super.mouseDown(mx, my, button);
		}

		@Override
		public void mouseDrag(float mx, float my) {
			if (dragging) {
				setFrom(my);
			}
		}

		@Override
		public void mouseUp(float mx, float my, int button) {
			dragging = false;
			super.mouseUp(mx, my, button);
		}

		@Override
		public boolean keyDown(KeyInput event) {
			float by = event.hasShiftDown() ? 15 : 3;
			if (event.isUp() || event.isDown()) {
				hue = Math.clamp(hue + (event.isUp() ? -by : by), 0, 359.99F);
				fromHsv();
				return true;
			}
			return super.keyDown(event);
		}

		@Override
		public String narration() {
			return "Hue " + Math.round(hue) + " degrees. Up and down arrows to change";
		}

		@Override
		protected void paint(Canvas c) {
			int x0 = c.px(x);
			int y0 = c.px(y);
			c.roundLinear(x0, y0, c.px(x + w) - x0, c.px(y + h) - y0, c.px(r(10)), 180, HUES, 1);
			MenuStyle.ring(c, x, y, w, h, r(10), MenuStyle.edge(), 0x30FFFFFF);
			thumb(c, x + w / 2, y + hue / 360 * h, Hsv.rgb(hue, 1, 1), Math.max(press, dragging ? 1 : 0));
			MenuStyle.focusRing(c, x, y, w, h, r(10), focus);
		}
	}

	/** The recent colours, two rows of four; the current one ringed in ember; each named in its tooltip. */
	private final class Swatches extends Pressable {
		private static final int COLUMNS = 4;
		private int hot = -1;

		Swatches() {
			super(null);
			name("Recent colors");
		}

		/** A cell's width: four across the column. */
		private float cell() {
			return (w - (COLUMNS - 1) * r(CELL_GAP)) / COLUMNS;
		}

		/** A cell's height: its width, at most {@link #CELL_H}, so a wide column keeps its rows low. */
		private float cellH() {
			return Math.min(cell(), r(CELL_H));
		}

		private int at(float mx, float my) {
			float cell = cell();
			float cellH = cellH();
			int col = (int) Math.floor((mx - x) / (cell + r(CELL_GAP)));
			int row = (int) Math.floor((my - y) / (cellH + r(CELL_GAP)));
			if (col < 0 || col >= COLUMNS || row < 0 || row > 1) {
				return -1;
			}
			float inX = mx - x - col * (cell + r(CELL_GAP));
			float inY = my - y - row * (cellH + r(CELL_GAP));
			int i = row * COLUMNS + col;
			return inX <= cell && inY <= cellH && i < recent.length ? i : -1;
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			int i = at(mx, my);
			if (i >= 0) {
				apply(recent[i]);
			}
			return super.mouseDown(mx, my, button);
		}

		@Override
		public String narration() {
			return "Recent colors, " + recent.length + " colors";
		}

		@Override
		protected void paint(Canvas c) {
			KitScreen screen = screen();
			hot = hovered() && screen != null ? at(screen.mouseX(), screen.mouseY()) : -1;
			tooltip(hot >= 0 ? colorName(recent[hot]) : null);
			float cell = cell();
			float cellH = cellH();
			int now = current();
			boolean clearNow = isClear();
			for (int i = 0; i < recent.length; i++) {
				float sx = x + (i % COLUMNS) * (cell + r(CELL_GAP));
				float sy = y + (i / COLUMNS) * (cellH + r(CELL_GAP));
				float grow = i == hot ? r(3) : 0;
				MenuStyle.box(c, sx - grow, sy - grow, cell + 2 * grow, cellH + 2 * grow, r(10), 0xFF000000 | recent[i]);
				MenuStyle.ring(c, sx - grow, sy - grow, cell + 2 * grow, cellH + 2 * grow, r(10), MenuStyle.edge(), 0x30FFFFFF);
				if (!clearNow && recent[i] == now) {
					float out = r(4);
					MenuStyle.ring(c, sx - out, sy - out, cell + 2 * out, cellH + 2 * out, r(13), Math.max(2, Math.round(MenuStyle.edge() * 2F)),
						MenuStyle.ACCENT);
				}
			}
			MenuStyle.focusRing(c, x, y, w, h, r(10), focus);
		}
	}

	/** Transparent, for a colour that can be see-through: the empty box with its red line, and the word. */
	private final class TransparentChip extends Pressable {
		private final Label label = new Label(Type.VALUE, "Transparent");

		TransparentChip() {
			super(null);
			name("Transparent");
		}

		/** The empty box, the word and the room around them. */
		@Override
		public float prefWidth() {
			return r(8) + (r(BUTTON_H) - r(16)) + r(12) + label.width(measure()) + r(18);
		}

		private void pick() {
			Clear c = clear;
			if (c != null) {
				c.clear().run();
				hex.setText("");
			}
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			if (button == Kit.LEFT_BUTTON) {
				pick();
			}
			return super.mouseDown(mx, my, button);
		}

		@Override
		public void activate() {
			pick();
		}

		@Override
		public String narration() {
			return "Transparent" + (isClear() ? ", chosen" : "");
		}

		@Override
		protected void paint(Canvas c) {
			boolean on = isClear();
			MenuStyle.box(c, x, y, w, h, r(12), Palette.withAlpha(MenuStyle.TEXT, 0.03F + 0.05F * hover));
			MenuStyle.ring(c, x, y, w, h, r(12), on ? Math.max(2, Math.round(MenuStyle.edge() * 2F)) : MenuStyle.edge(),
				on ? MenuStyle.ACCENT : 0x30FFFFFF);
			float box = h - r(16);
			MenuStyle.transparent(c, x + r(8), y + r(8), box, box, r(8));
			MenuStyle.fitted(c, label, x + r(8) + box + r(12), y + h / 2, on ? MenuStyle.TEXT : MenuStyle.ROW_TEXT, w - box - r(28));
			MenuStyle.focusRing(c, x, y, w, h, r(12), focus);
		}
	}
}
