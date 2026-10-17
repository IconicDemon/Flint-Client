package com.flintclient.client.control.menu;

import static com.flintclient.client.control.menu.MenuStyle.r;

import com.flintclient.client.control.menu.MenuStyle.Label;
import com.flintclient.client.control.menu.MenuStyle.Type;
import com.flintclient.client.control.menu.PageControls.TextButton;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.TextField;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The colour picker: a small panel under the swatch that opened it, over the page (not dimming it, so the preview shows
 * each colour as it is picked). Sixteen colours to pick from, a hex field (a valid {@code #RRGGBB} or {@code #RGB} applies
 * as it is typed; anything else is marked and never applied), Default (the setting's own colour) and Done. Esc, Done or a
 * click outside the panel closes it. It covers the whole window while open so that outside click lands here.
 */
final class ColorPicker extends Node {
	private static final int[] PALETTE = {
		0xFFFFFF, 0xD8D1C8, 0x8E857A, 0x000000, 0xFF8139, 0xFFB547, 0xFFE36E, 0x9BE08A,
		0x4CC27A, 0x4FD1C5, 0x8FD3FF, 0x5B8CFF, 0xA88BFF, 0xFF7AC6, 0xFF5A5A, 0x8B5E3C};
	private static final String[] NAMES = {"White", "Light grey", "Grey", "Black", "Flint orange", "Amber", "Yellow", "Lime", "Green",
		"Teal", "Sky", "Blue", "Violet", "Pink", "Red", "Brown"};
	private static final float PANEL_W = 600;
	private static final float SWATCH = 56;
	private static final float GAP = 12;
	private static final float PAD = 26;

	private final Label title = new Label(Type.SECTION_TITLE, "");
	private final Label hexNote = new Label(Type.ROW_NOTE, "");
	private final Swatches swatches = add(new Swatches());
	private final TextField hex = add(new TextField("#RRGGBB", 7));
	private final TextButton reset;
	private final TextButton done;
	private @Nullable IntSupplier get;
	private @Nullable IntConsumer set;
	private int defaultRgb;
	private @Nullable Node anchor;
	private @Nullable FontFace hexFace;
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
	void show(Node anchor, String name, int defaultRgb, IntSupplier get, IntConsumer set) {
		this.anchor = anchor;
		this.get = get;
		this.set = set;
		this.defaultRgb = defaultRgb;
		title.set(name);
		hex.setText(RgbSetting.hex(get.getAsInt()));
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
		setVisible(false);
		KitScreen screen = screen();
		Node back = anchor;
		if (screen != null) {
			screen.focus(back != null && screen.focusVisible() ? back : null);
		}
		anchor = null;
		get = null;
		set = null;
	}

	private void apply(int rgb) {
		IntConsumer target = set;
		if (target != null) {
			target.accept(rgb & 0xFFFFFF);
			hex.setText(RgbSetting.hex(rgb));
		}
	}

	private void typed(String text) {
		int rgb = RgbSetting.parseHex(text);
		IntConsumer target = set;
		if (rgb >= 0 && target != null) {
			target.accept(rgb);
		}
	}

	private int current() {
		IntSupplier source = get;
		return source != null ? source.getAsInt() : 0;
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
	public boolean keyDown(KeyEvent event) {
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
		float rows = r(SWATCH) * 2 + r(GAP);
		ph = r(PAD) + r(40) + r(18) + rows + r(24) + r(64) + r(PAD);
		float ax = from != null ? from.x() + from.width() - pw : x + (w - pw) / 2;
		float below = from != null ? from.y() + from.height() + r(12) : y + (h - ph) / 2;
		float above = from != null ? from.y() - r(12) - ph : below;
		px = Math.clamp(ax, x + r(16), x + w - pw - r(16));
		py = below + ph <= y + h - r(16) ? below : Math.max(y + r(16), above);
		float inner = px + r(PAD);
		float top = py + r(PAD) + r(40) + r(18);
		swatches.setBounds(inner, top, pw - 2 * r(PAD), rows);
		float rowY = top + rows + r(24);
		float doneW = done.prefWidth();
		float resetW = reset.prefWidth();
		done.setBounds(px + pw - r(PAD) - doneW, rowY, doneW, r(64));
		reset.setBounds(done.x() - r(12) - resetW, rowY, resetW, r(64));
		hex.setBounds(inner, rowY + r(4), Math.max(r(140), reset.x() - r(16) - inner), r(56));
		FontFace face = Type.VALUE.face();
		if (face != hexFace) {
			hexFace = face;
			hex.look(new TextField.Look(face, MenuStyle.FIELD, MenuStyle.FIELD_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS, r(12),
				r(18), 0, 0, MenuStyle.ROW_TEXT, MenuStyle.LABEL, MenuStyle.LABEL));
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
		MenuStyle.framed(c, px, py, pw, ph, r(22), MenuStyle.WINDOW, MenuStyle.SECTION_EDGE);
		MenuStyle.text(c, title, px + r(PAD), py + r(PAD) + r(20), MenuStyle.ROW_TEXT);
		// The colour now, beside the title.
		float chip = r(40);
		MenuStyle.box(c, px + pw - r(PAD) - chip, py + r(PAD), chip, chip, r(10), 0xFF000000 | current());
		MenuStyle.ring(c, px + pw - r(PAD) - chip, py + r(PAD), chip, chip, r(10), MenuStyle.edge(), 0x40FFFFFF);
		// A hex that isn't a colour is marked, and never applied.
		boolean invalid = !hex.text().isBlank() && RgbSetting.parseHex(hex.text()) < 0;
		if (invalid) {
			hexNote.set("Use #RRGGBB, like #FF8139");
			MenuStyle.ring(c, hex.x(), hex.y(), hex.width(), hex.height(), r(12), Math.max(1, Math.round(MenuStyle.edge() * 1.5F)),
				MenuStyle.DANGER);
			MenuStyle.fitted(c, hexNote, hex.x() + r(4), hex.y() - r(16), MenuStyle.DANGER_TEXT, hex.width());
		}
	}

	/** The sixteen colours, two rows of eight; the current one ringed in ember. */
	private final class Swatches extends Pressable {
		private int hot = -1;

		Swatches() {
			super(null);
			name("Colors");
		}

		private int at(float mx, float my) {
			float cell = (w - 7 * r(GAP)) / 8;
			int col = (int) Math.floor((mx - x) / (cell + r(GAP)));
			int row = (int) Math.floor((my - y) / (r(SWATCH) + r(GAP)));
			if (col < 0 || col > 7 || row < 0 || row > 1) {
				return -1;
			}
			float inX = mx - x - col * (cell + r(GAP));
			float inY = my - y - row * (r(SWATCH) + r(GAP));
			return inX <= cell && inY <= r(SWATCH) ? row * 8 + col : -1;
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			int i = at(mx, my);
			if (i >= 0) {
				apply(PALETTE[i]);
			}
			return super.mouseDown(mx, my, button);
		}

		@Override
		public String narration() {
			int now = current();
			for (int i = 0; i < PALETTE.length; i++) {
				if (PALETTE[i] == now) {
					return "Colors, " + NAMES[i] + " chosen";
				}
			}
			return "Colors, " + RgbSetting.hex(now) + " chosen";
		}

		@Override
		protected void paint(Canvas c) {
			KitScreen screen = screen();
			hot = hovered() && screen != null ? at(screen.mouseX(), screen.mouseY()) : -1;
			float cell = (w - 7 * r(GAP)) / 8;
			int now = current();
			for (int i = 0; i < PALETTE.length; i++) {
				float sx = x + (i % 8) * (cell + r(GAP));
				float sy = y + (i / 8) * (r(SWATCH) + r(GAP));
				float grow = i == hot ? r(3) : 0;
				MenuStyle.box(c, sx - grow, sy - grow, cell + 2 * grow, r(SWATCH) + 2 * grow, r(12), 0xFF000000 | PALETTE[i]);
				MenuStyle.ring(c, sx - grow, sy - grow, cell + 2 * grow, r(SWATCH) + 2 * grow, r(12), MenuStyle.edge(), 0x30FFFFFF);
				if (PALETTE[i] == now) {
					float out = r(5);
					MenuStyle.ring(c, sx - out, sy - out, cell + 2 * out, r(SWATCH) + 2 * out, r(15), Math.max(2, Math.round(MenuStyle.edge() * 2F)),
						MenuStyle.ACCENT);
				}
			}
			MenuStyle.focusRing(c, x, y, w, h, r(12), focus);
		}
	}
}
