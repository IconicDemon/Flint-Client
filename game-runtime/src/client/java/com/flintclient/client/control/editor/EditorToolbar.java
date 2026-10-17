package com.flintclient.client.control.editor;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Button;
import com.flintclient.client.ui.kit.IconButton;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.Tokens2;
import com.flintclient.client.ui.kit.Type;
import net.minecraft.client.input.InputQuirks;

/**
 * The editor's toolbar: a compact capsule at the top centre (or the bottom centre, when a module sits under the top
 * place), 20 units tall in the Control Center's colour with its hairline edge and a soft shadow. It holds Undo and
 * Redo, snapping and Minecraft's HUD areas (toggles), "+ Widgets" (the palette), Reset, then Cancel and Done (which
 * saves). H tucks every tool away and the toolbar becomes a small pill, "Editing HUD · H"; H again brings them back.
 * While a module is being dragged, or the selected module is under it and the pointer isn't on it, it fades back.
 * Its background isn't a control: presses there reach the module beneath, and so does the wheel.
 */
final class EditorToolbar extends Node {
	static final float HEIGHT = 20;
	/** From the screen's top (or bottom) edge. */
	static final float TOP = 8;
	private static final float PAD = 6;
	private static final float BUTTON = 16;
	private static final float GAP = 2;
	private static final float GROUP = 12;
	private static final float FADE_TAU = 0.08F;
	private static final float DONE_H = 14;

	private final HudEditor editor;
	private final IconButton undo;
	private final IconButton redo;
	private final IconButton snap;
	private final IconButton areas;
	private final Button modules;
	private final IconButton reset;
	private final Button cancel;
	private final Button done;
	private final Pill pill;
	private float fade = 1.0F;
	private float savedOpacity = 1.0F;
	private final float[] dividers = new float[2];
	private boolean shownAreas;
	private boolean shownSnap;
	private boolean shownHidden;

	EditorToolbar(HudEditor editor) {
		this.editor = editor;
		this.h = HEIGHT;
		// The shortcut is named as the platform has it (Command on a Mac, as Minecraft's own text fields use).
		String modifier = InputQuirks.REPLACE_CTRL_KEY_WITH_CMD_KEY ? "Cmd" : "Ctrl";
		undo = add(new IconButton(Icon.UNDO, "Undo (" + modifier + "+Z)", BUTTON, editor::undo).iconSize(9));
		redo = add(new IconButton(Icon.REDO, "Redo (Shift+" + modifier + "+Z)", BUTTON, editor::redo).iconSize(9));
		snap = add(new IconButton(Icon.MAGNET, "Snapping: on", BUTTON, this::toggleSnap).iconSize(9));
		areas = add(new IconButton(Icon.LAYERS, "Minecraft's HUD: shown", BUTTON, this::toggleAreas).iconSize(9));
		modules = add(new Button("Widgets", Button.Variant.GHOST, Type.META, editor::togglePalette).icon(Icon.PLUS).iconSize(7));
		reset = add(new IconButton(Icon.RESET, "Reset positions", BUTTON, editor::askReset).iconSize(9));
		cancel = add(new Button("Cancel", Button.Variant.GHOST, Type.META, editor::cancel));
		done = add(new Button("Done", Button.Variant.PRIMARY, editor::save));
		done.tooltip("Saves and returns to the game");
		pill = add(new Pill(editor));
		pill.setVisible(false);
		syncToggles();
	}

	private void toggleAreas() {
		editor.setShowAreas(!editor.showAreas());
		syncToggles();
		if (screen() != null && focused()) {
			screen().announce(areas);
		}
	}

	private void toggleSnap() {
		editor.setSnapping(!editor.snapping());
		syncToggles();
		if (screen() != null && focused()) {
			screen().announce(snap);
		}
	}

	private void syncToggles() {
		shownAreas = editor.showAreas();
		areas.setActive(shownAreas);
		areas.setName(shownAreas ? "Minecraft's HUD: shown" : "Minecraft's HUD: hidden");
		shownSnap = editor.snapping();
		snap.setActive(shownSnap);
		snap.setName(shownSnap ? "Snapping: on (hold Alt to move freely)" : "Snapping: off");
	}

	Button widgetsButton() {
		return modules;
	}

	static float top(float height, boolean bottom) {
		return bottom ? height - TOP - HEIGHT : TOP;
	}

	/** Centres the capsule (or, with the tools hidden, the small pill) at the top or bottom of the screen. */
	void place(float width, float height, boolean bottom) {
		float w = editor.chromeHidden() ? pill.prefWidth() : Math.min(prefWidth(), width - 8);
		setBounds(Math.round((width - w) / 2), top(height, bottom), w, HEIGHT);
	}

	@Override
	public boolean scroll(float mx, float my, double amount) {
		return editor.scrollAt(mx, my, amount);
	}

	@Override
	public float prefWidth() {
		return PAD + 2 * BUTTON + GAP + GROUP + 2 * BUTTON + GAP + 4 + modules.prefWidth() + 4 + BUTTON + GROUP + cancel.prefWidth() + 10
			+ done.prefWidth() + 3;
	}

	@Override
	protected void layout() {
		boolean hidden = editor.chromeHidden();
		pill.setVisible(hidden);
		for (Node node : new Node[] {undo, redo, snap, areas, modules, reset, cancel, done}) {
			node.setVisible(!hidden);
		}
		pill.setBounds(x, y, w, h);
		float cy = y + (h - BUTTON) / 2;
		float ty = y + (h - Button.TEXT_HEIGHT) / 2;
		float at = x + PAD;
		undo.setBounds(at, cy, BUTTON, BUTTON);
		at += BUTTON + GAP;
		redo.setBounds(at, cy, BUTTON, BUTTON);
		at += BUTTON + GROUP / 2;
		dividers[0] = at;
		at += GROUP / 2;
		snap.setBounds(at, cy, BUTTON, BUTTON);
		at += BUTTON + GAP;
		areas.setBounds(at, cy, BUTTON, BUTTON);
		at += BUTTON + 4;
		float mw = modules.prefWidth();
		modules.setBounds(at, ty, mw, Button.TEXT_HEIGHT);
		at += mw + 4;
		reset.setBounds(at, cy, BUTTON, BUTTON);
		at += BUTTON + GROUP / 2;
		dividers[1] = at;
		float dw = done.prefWidth();
		done.setBounds(x + w - 3 - dw, y + (h - DONE_H) / 2, dw, DONE_H);
		float cw = cancel.prefWidth();
		cancel.setBounds(done.x() - 10 - cw, ty, cw, Button.TEXT_HEIGHT);
	}

	@Override
	protected void paint(Canvas c) {
		syncHistory();
		if (shownHidden != editor.chromeHidden()) {
			shownHidden = editor.chromeHidden();
			invalidate();
		}
		fade = Kit.approach(fade, editor.toolbarFaded() && !editor.chromeHidden() ? 0.3F : 1.0F, FADE_TAU);
		savedOpacity = c.opacity();
		c.setOpacity(savedOpacity * fade);
		paintCapsule(c, x, y, w, h);
		if (!editor.chromeHidden()) {
			int top = c.px(y + 5);
			int bottom = c.px(y + h - 5);
			for (float divider : dividers) {
				int dx = c.px(divider);
				c.fill(dx, top, dx + 1, bottom, Tokens2.HAIRLINE_STRONG);
			}
		}
	}

	static void paintCapsule(Canvas c, float x, float y, float w, float h) {
		paintCard(c, x, y, w, h, h / 2);
	}

	/** A floating card in the Control Center's colour with radius {@code radius}, its soft shadow and its top highlight. */
	static void paintCard(Canvas c, float x, float y, float w, float h, float radius) {
		int x0 = c.px(x);
		int y0 = c.px(y);
		int pw = c.px(x + w) - x0;
		int ph = c.px(y + h) - y0;
		int r = Math.min(c.px(radius), Math.min(pw, ph) / 2);
		c.roundRectCorners(x0 - c.px(1), y0 + c.px(1), pw + c.px(2), ph + c.px(2), r + c.px(1), 0x1A000000);
		c.roundRectCorners(x0, y0 + c.px(2), pw, ph, r, 0x2E000000);
		c.roundRectCorners(x0, y0, pw, ph, r, 0xFF131211);
		c.ringCorners(x0, y0, pw, ph, r, 1, Kit.prefs().highContrast() ? Tokens2.hairlineStrong() : 0x14FFFFFF);
		c.ringQuarter(x0, y0, r, 1, Canvas.TOP_LEFT, Tokens2.DECK_EDGE);
		c.fill(x0 + r, y0, x0 + pw - r, y0 + 1, Tokens2.DECK_EDGE);
		c.ringQuarter(x0 + pw - r, y0, r, 1, Canvas.TOP_RIGHT, Tokens2.DECK_EDGE);
	}

	@Override
	protected void paintOver(Canvas c) {
		c.setOpacity(savedOpacity);
	}

	private void syncHistory() {
		boolean canUndo = editor.session().canUndo();
		if (canUndo != undo.enabled()) {
			if (canUndo) {
				undo.setEnabled(true);
			} else {
				undo.setDisabled("Nothing to undo");
			}
		}
		boolean canRedo = editor.session().canRedo();
		if (canRedo != redo.enabled()) {
			if (canRedo) {
				redo.setEnabled(true);
			} else {
				redo.setDisabled("Nothing to redo");
			}
		}
		if (shownAreas != editor.showAreas() || shownSnap != editor.snapping()) {
			syncToggles();
		}
	}

	/** With the tools hidden: "Editing HUD · H"; a click brings them back. */
	private static final class Pill extends Pressable {
		private final TextLine label = new TextLine(Type.META, "Editing HUD");
		private final TextLine key = new TextLine(Type.META, "H");

		Pill(HudEditor editor) {
			super(editor::toggleChrome);
			name("Show the editing tools (H)");
		}

		@Override
		public float prefWidth() {
			Canvas c = measure();
			return 38 + (float) (c.width(label) + c.width(key)) / c.scale();
		}

		@Override
		protected void paint(Canvas c) {
			int center = c.px(y + h / 2);
			int left = c.px(x + 10);
			c.roundRect(left, center - c.px(2), c.px(4), c.px(4), c.px(2), Tokens2.EMBER);
			int textX = left + c.px(8);
			c.text(label, textX, c.baselineFor(label, center), Tokens2.TEXT_DIM);
			int keyX = textX + c.width(label) + c.px(6);
			int kw = c.width(key) + c.px(8);
			int kh = c.px(12);
			c.ring(keyX, center - kh / 2, kw, kh, c.px(3), 1, Tokens2.HAIRLINE_STRONG);
			c.text(key, keyX + c.px(4), c.baselineFor(key, center), Tokens2.muted());
			Kit.focusRing(c, x, y, w, h, h / 2, focus);
		}
	}
}
