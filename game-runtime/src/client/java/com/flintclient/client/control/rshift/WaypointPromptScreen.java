package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;
import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.Motion;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.TextField;
import org.jspecify.annotations.Nullable;

/**
 * The waypoint key's question: a small window over the game asking what to call the waypoint about to be made at the
 * coordinates just picked (shown), with its colour, and Create or Cancel. The name starts as the next free one, selected, so
 * typing replaces it and Enter makes it as it is; Escape cancels. Fades and rises in like Flint's menus. Same window on
 * every Minecraft line: each line's waypoints are saved by {@code onCreate}.
 */
public final class WaypointPromptScreen extends KitScreen {
	/** Makes the waypoint: its name (never blank) and colour (RGB). */
	public interface Create {
		void create(String name, int rgb);
	}

	/** The colours offered: Flint orange, the palette new waypoints cycle through, and white. */
	static final int[] COLORS = {0xFF8139, 0xFF2F20, 0xFF9F1C, 0xFFE530, 0x21D85B, 0x19B7FF, 0x5D38FF, 0xF045B7, 0xF2F2F2};
	private static final long CLOSE_TIMEOUT_MILLIS = 400;

	private final @Nullable Object parent;
	private final MenuMotion motion = new MenuMotion();
	private final String suggested;
	private final String where;
	private final int rgb;
	private final Create onCreate;
	private @Nullable Prompt prompt;
	private boolean closing;
	private boolean framed;
	private long closingSince;

	/**
	 * Asks about a waypoint at ({@code x}, {@code y}, {@code z}) in {@code place} ("Overworld"); {@code rgb} is the colour it
	 * would take. Closing goes back to {@code parent} (null: the game).
	 */
	public WaypointPromptScreen(@Nullable Object parent, String suggested, int x, int y, int z, String place, int rgb, Create onCreate) {
		super("New waypoint", 240, 160);
		this.parent = parent;
		this.suggested = suggested;
		this.where = "X " + x + "   Y " + y + "   Z " + z + (place.isEmpty() ? "" : "   ·   " + place);
		this.rgb = rgb & 0xFFFFFF;
		this.onCreate = onCreate;
	}

	@Override
	protected void build(Node root) {
		prompt = root.add(new Prompt());
	}

	@Override
	protected void layoutContent(float width, float height) {
		if (prompt != null) {
			prompt.setBounds(0, 0, width, height);
		}
	}

	@Override
	protected int computeUiScale(int windowW, int windowH) {
		return MenuStyle.apply(windowW, windowH);
	}

	@Override
	protected boolean minecraftFont() {
		return true;
	}

	@Override
	protected int minecraftFontScale() {
		return MenuStyle.pixelScale();
	}

	@Override
	protected TooltipLook tooltipLook() {
		return MenuStyle.tooltipLook();
	}

	@Override
	protected void initialFocus(boolean keyboard) {
		if (prompt != null) {
			focus(prompt.name);
			prompt.name.state().selectAll();
		}
	}

	@Override
	protected void shown() {
		super.shown();
		motion.open();
	}

	@Override
	protected void update() {
		if (closing && Clock.millis() - closingSince > CLOSE_TIMEOUT_MILLIS) {
			finish();
		}
	}

	@Override
	protected void closeRequested() {
		if (!closing) {
			closing = true;
			closingSince = Clock.millis();
			motion.close();
		}
	}

	@Override
	protected void onEscape() {
		closeRequested();
	}

	@Override
	protected boolean acceptsInput() {
		return motion.opening();
	}

	private void finish() {
		closing = false;
		if (hostIsShown()) {
			hostShow(parent);
		}
	}

	private void create() {
		if (prompt == null || closing) {
			return;
		}
		String name = prompt.name.text().strip();
		onCreate.create(name.isEmpty() ? suggested : name, COLORS[prompt.color]);
		com.flintclient.client.ui.kit.Kit.clickSound();
		closeRequested();
	}

	/** The game behind, dimmed with the fade; the window rises in. */
	@Override
	protected void paintBackdrop(Canvas c) {
		boolean first = !framed;
		framed = true;
		MenuStyle.applyTheme(ControlState.settings().menuTheme());
		if (motion.advance(first ? 0 : Motion.frameSeconds())) {
			finish();
		}
		float shown = motion.shown();
		setOpacity(shown);
		setContentShift(motion.offset(r(12), r(6)));
		setContentZoom(motion.zoom(0.97F, 0.985F));
		if (!hostInWorld()) {
			hostPaintMenuBackdrop(c);
			c.frame().nextLayer();
		}
		float before = c.opacity();
		c.setOpacity(before * shown * 0.7F);
		c.fill(0, 0, c.px(hostGuiWidth()), c.px(hostGuiHeight()), MenuStyle.SCRIM);
		c.setOpacity(before);
	}

	/** The window: a pin and "New waypoint", where it is, the name, the colour, and Cancel and Create. */
	private final class Prompt extends Node {
		private static final float W = 600;
		private static final float H = 392;
		private static final float PAD = 32;
		private final Label title = new Label(Type.PAGE_TITLE, "New waypoint");
		private final Label place = new Label(Type.ROW_NOTE, where);
		private final Label nameCaption = new Label(Type.CAPTION, "NAME");
		private final Label colorCaption = new Label(Type.CAPTION, "COLOR");
		private final NameField name;
		private final Swatch[] swatches = new Swatch[COLORS.length];
		private final PageControls.TextButton cancel;
		private final PageControls.TextButton make;
		private int color;
		private float px;
		private float py;
		private float pw;
		private float ph;

		Prompt() {
			name = add(new NameField());
			name.setText(suggested);
			name.onSubmit(text -> create());
			name.onEscape(WaypointPromptScreen.this::closeRequested);
			color = 0;
			for (int i = 0; i < COLORS.length; i++) {
				if (COLORS[i] == rgb) {
					color = i;
				}
			}
			for (int i = 0; i < COLORS.length; i++) {
				int index = i;
				swatches[i] = add(new Swatch(index, () -> color = index));
			}
			cancel = add(new PageControls.TextButton("Cancel", null, PageControls.Look.PLAIN, WaypointPromptScreen.this::closeRequested));
			make = add(new PageControls.TextButton("Create", Glyph.PLUS, PageControls.Look.ACCENT, WaypointPromptScreen.this::create));
		}

		@Override
		protected void layout() {
			pw = Math.min(w - d(32), d(W));
			ph = d(H);
			px = x + (w - pw) / 2;
			py = y + (h - ph) / 2;
			float left = px + d(PAD);
			float inner = pw - 2 * d(PAD);
			name.setBounds(left, py + d(150), inner, d(52));
			float s = d(32);
			float gap = Math.min(d(14), (inner - COLORS.length * s) / Math.max(1, COLORS.length - 1));
			for (int i = 0; i < swatches.length; i++) {
				swatches[i].setBounds(left + i * (s + gap), py + d(250), s, s);
			}
			float bh = r(PageControls.TextButton.H);
			float mw = Math.max(make.prefWidth(), d(150));
			float cw = Math.max(cancel.prefWidth(), d(130));
			make.setBounds(px + pw - d(PAD) - mw, py + ph - d(PAD) - bh, mw, bh);
			cancel.setBounds(make.x() - d(12) - cw, make.y(), cw, bh);
		}

		@Override
		protected void paint(Canvas c) {
			float rad = MenuStyle.radius();
			MenuStyle.block(c, px, py, pw, ph, rad, 0, 0);
			float left = px + d(PAD);
			float disc = d(44);
			float cy = py + d(PAD) + disc / 2;
			MenuStyle.box(c, left, cy - disc / 2, disc, disc, disc / 2, Palette.withAlpha(0xFF000000 | COLORS[color], 0.18F));
			Glyph.PIN.draw(c, left + disc / 2, cy, d(20), 0xFF000000 | COLORS[color]);
			float tx = left + disc + d(16);
			MenuStyle.text(c, title, tx, cy - d(10), MenuStyle.WORDMARK);
			MenuStyle.fitted(c, place, tx, cy + d(14), MenuStyle.NEUTRAL, px + pw - d(PAD) - tx);
			MenuStyle.text(c, nameCaption, left, py + d(134), MenuStyle.NEUTRAL);
			MenuStyle.text(c, colorCaption, left, py + d(234), MenuStyle.NEUTRAL);
		}
	}

	/** A name field in the window's look. */
	private static final class NameField extends TextField {
		private @Nullable FontFace face;

		NameField() {
			super("Name", 32);
		}

		@Override
		protected void layout() {
			FontFace now = Type.VALUE.face();
			if (now != face) {
				face = now;
				look(new TextField.Look(now, MenuStyle.FIELD, MenuStyle.FIELD_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS, r(12), r(18), 0, 0,
					MenuStyle.ROW_TEXT, MenuStyle.LABEL, MenuStyle.LABEL));
			}
			super.layout();
		}
	}

	/** One colour: a round swatch, ringed while chosen. */
	private final class Swatch extends Pressable {
		private final int index;
		private float on = -1;

		Swatch(int index, Runnable choose) {
			super(choose);
			this.index = index;
			name("Color " + (index + 1));
		}

		@Override
		protected void paint(Canvas c) {
			on = MenuStyle.ease(on, prompt != null && prompt.color == index);
			int rgbFull = 0xFF000000 | COLORS[index];
			float grow = d(2) * hover;
			MenuStyle.box(c, x - grow / 2, y - grow / 2, w + grow, h + grow, (h + grow) / 2, rgbFull);
			if (on > 0.01F) {
				float out = d(4);
				MenuStyle.ring(c, x - out, y - out, w + 2 * out, h + 2 * out, (h + 2 * out) / 2, Math.max(1, c.px(d(2))),
					Palette.withAlpha(MenuStyle.TEXT, on));
			}
			MenuStyle.focusRing(c, x, y, w, h, h / 2, focus);
		}
	}
}
