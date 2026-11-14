package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;
import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.CrosshairDrawing;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods.Crosshair;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.PageControls.Segmented;
import com.flintclient.client.control.rshift.PageControls.TextButton;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;

/**
 * Crosshair's drawing pad, over its page: the drawn crosshair's grid (57 by 57, the middle marked), drawn with the left mouse
 * button and erased with the right one (or Draw and Erase for the left), a stroke filling every square it passes over; the
 * live preview beside it at the game's size, Clear and Done. The first stroke makes Drawn the crosshair's style, so what you
 * draw is what you see in the game. Saved when a stroke ends. Flint's own menu input, so it works the same on every Minecraft
 * line (the old editor, a separate Minecraft screen, waited for mouse button 0, which Minecraft 26.3 no longer calls the left
 * button, and 1.8.9 had no editor at all).
 */
final class CrosshairPad extends Node {
	private static final float PANEL_W = 860;
	private static final float PANEL_H = 600;
	private static final float PAD = 28;
	/** The Drawn style's index in Crosshair's style list. */
	private static final int DRAWN = 7;
	private final Label title = new Label(Type.PAGE_TITLE, "Draw your crosshair");
	private final Label hint = new Label(Type.ROW_NOTE, "Left button draws");
	private final Label brushLabel = new Label(Type.ROW_NOTE, "Size");
	private final Label hint2 = new Label(Type.ROW_NOTE, "Right button erases");
	private final Label hint3 = new Label(Type.ROW_NOTE, "Drag to paint a line");
	private final Label previewLabel = new Label(Type.ROW_NOTE, "In the game");
	private final Label count = new Label(Type.ROW_NOTE, "");
	private final Grid grid;
	private final Segmented mode;
	/** The brush: 1, 2 or 3 squares across (1, the thinnest, a single square), for drawing and erasing alike. */
	private final Segmented brush;
	private int brushSize = 1;
	private final TextButton clear;
	private final TextButton done;
	private final CrosshairPreview.Box preview = new CrosshairPreview.Box("crosshair_preview_pad");
	private boolean erasing;
	/** The stroke under way: whether it draws, the square it was last at; -1 with none. */
	private boolean strokeDraws;
	private int lastX = -1;
	private int lastY = -1;
	private boolean stroking;
	private float px;
	private float py;
	private float pw;
	private float ph;
	/** The grid in window pixels: its corner and a square's side (whole pixels, so the squares stay sharp). */
	private int gridX;
	private int gridY;
	private int cell = 1;
	private float gridUnits;
	/** Pixels per UI unit as last painted (the pointer is matched against the grid in pixels). */
	private float pixelsPerUnit = 1;

	CrosshairPad() {
		grid = add(new Grid());
		mode = add(new Segmented(new String[] {"Draw", "Erase"}, () -> erasing ? 1 : 0, i -> erasing = i == 1));
		mode.name("Pen");
		brush = add(new Segmented(new String[] {"1", "2", "3"}, () -> brushSize - 1, i -> brushSize = i + 1));
		brush.name("Brush size");
		brush.tooltip("Brush size\n1 is a single square, the thinnest");
		clear = add(new TextButton("Clear", Glyph.TRASH, PageControls.Look.PLAIN, this::clearAll));
		done = add(new TextButton("Done", Glyph.CHECK, PageControls.Look.ACCENT, this::close));
		setVisible(false);
	}

	void show() {
		setVisible(true);
		invalidate();
	}

	/** Closes it if it's open; whether it was. */
	boolean closeIfOpen() {
		if (!visible()) {
			return false;
		}
		close();
		return true;
	}

	private void close() {
		endStroke();
		setVisible(false);
		KitScreen screen = screen();
		if (screen != null) {
			screen.focus(null);
		}
	}

	private void clearAll() {
		CrosshairDrawing.shared().clear();
		CrosshairDrawing.saveShared();
	}

	@Override
	public boolean interactive() {
		return visible();
	}

	@Override
	public boolean handlesEscape() {
		return visible();
	}

	@Override
	public boolean keyDown(KeyInput event) {
		if (event.isEscape()) {
			close();
			return true;
		}
		return super.keyDown(event);
	}

	// ---------- drawing ----------

	/** The square under window pixel ({@code wx}, {@code wy}), as {x, y}; null off the grid. */
	private int[] square(float mx, float my) {
		int size = CrosshairDrawing.shared().width();
		int wx = Math.round(mx * pixelsPerUnit) - gridX;
		int wy = Math.round(my * pixelsPerUnit) - gridY;
		int x = Math.floorDiv(wx, cell);
		int y = Math.floorDiv(wy, cell);
		return x >= 0 && y >= 0 && x < size && y < size ? new int[] {x, y} : null;
	}

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		if (!(mx >= px && mx < px + pw && my >= py && my < py + ph)) {
			// A click outside the pad closes it.
			close();
			return true;
		}
		int[] at = square(mx, my);
		if (at == null || button != Kit.LEFT_BUTTON && button != Kit.RIGHT_BUTTON) {
			return true;
		}
		strokeDraws = button == Kit.LEFT_BUTTON && !erasing;
		stroking = true;
		lastX = at[0];
		lastY = at[1];
		paintAt(at[0], at[1], at[0], at[1]);
		return true;
	}

	@Override
	public void mouseDrag(float mx, float my) {
		if (!stroking) {
			return;
		}
		int size = CrosshairDrawing.shared().width();
		// Off the grid the stroke follows along its edge, so a quick drag past it still reaches the border.
		int x = Math.clamp(Math.floorDiv(Math.round(mx * pixelsPerUnit) - gridX, cell), 0, size - 1);
		int y = Math.clamp(Math.floorDiv(Math.round(my * pixelsPerUnit) - gridY, cell), 0, size - 1);
		if (x != lastX || y != lastY) {
			paintAt(lastX, lastY, x, y);
			lastX = x;
			lastY = y;
		}
	}

	@Override
	public void mouseUp(float mx, float my, int button) {
		endStroke();
	}

	private void paintAt(int x0, int y0, int x1, int y1) {
		CrosshairDrawing drawing = CrosshairDrawing.shared();
		if (drawing.line(x0, y0, x1, y1, strokeDraws, brushSize) && strokeDraws) {
			// Drawing makes the drawn crosshair the one in use.
			ModuleState s = ControlState.layout().module(Crosshair.ID);
			if (s != null && s.option(Crosshair.TYPE) != DRAWN && s.set(Crosshair.TYPE, DRAWN)) {
				ControlState.settings().recordChange(Crosshair.ID);
			}
		}
	}

	private void endStroke() {
		if (stroking) {
			stroking = false;
			lastX = -1;
			lastY = -1;
			CrosshairDrawing.saveShared();
		}
	}

	// ---------- layout and painting ----------

	@Override
	protected void layout() {
		pw = Math.min(d(PANEL_W), w - d(32));
		ph = Math.min(d(PANEL_H), h - d(32));
		px = x + (w - pw) / 2;
		py = y + (h - ph) / 2;
		gridUnits = Math.max(d(120), ph - 2 * d(PAD) - d(48));
		// The grid in whole window pixels (each square as many as fit), and its node over it (for the pointer).
		pixelsPerUnit = measure().unitScale();
		int size = CrosshairDrawing.shared().width();
		cell = Math.max(2, Math.round(gridUnits * pixelsPerUnit) / size);
		gridX = Math.round((px + d(PAD)) * pixelsPerUnit);
		gridY = Math.round((py + d(PAD) + d(48)) * pixelsPerUnit);
		float side = cell * size / pixelsPerUnit;
		grid.setBounds(gridX / pixelsPerUnit, gridY / pixelsPerUnit, side, side);
		float right = px + pw - d(PAD);
		float column = px + d(PAD) + gridUnits + d(32);
		float bh = r(PageControls.TextButton.H) * 0.62F;
		mode.setBounds(column, py + d(PAD) + d(48), mode.prefWidth(), r(Segmented.H) * 0.8F);
		brush.setBounds(column, mode.y() + mode.height() + d(10), brush.prefWidth(), mode.height());
		float dw = done.prefWidth();
		done.setBounds(right - dw, py + ph - d(PAD) - bh, dw, bh);
		float cw = clear.prefWidth();
		clear.setBounds(done.x() - d(12) - cw, done.y(), cw, bh);
	}

	@Override
	protected void paint(Canvas c) {
		// The page dimmed under the pad.
		c.fill(c.px(x), c.px(y), c.px(x + w), c.px(y + h), Palette.withAlpha(0xFF000000, 0.55F));
		MenuStyle.block(c, px, py, pw, ph, MenuStyle.radius(), 0, 0);
		MenuStyle.fitted(c, title, px + d(PAD), py + d(PAD) + d(12), MenuStyle.TEXT, pw - 2 * d(PAD));
		CrosshairDrawing drawing = CrosshairDrawing.shared();
		int size = drawing.width();
		int side = cell * size;
		ModuleState s = ControlState.layout().module(Crosshair.ID);
		int ink = s == null ? 0xFFFFFFFF : 0xFF000000 | s.rgb(Crosshair.COLOR);
		c.fill(gridX, gridY, gridX + side, gridY + side, 0xFF101114);
		// Every square's edge, faint; the middle row and column a touch brighter.
		int middle = size / 2;
		for (int i = 0; i <= size; i++) {
			int line = i == middle || i == middle + 1 ? 0xFF2A2D33 : 0xFF181A1E;
			c.fill(gridX + i * cell, gridY, gridX + i * cell + 1, gridY + side, line);
			c.fill(gridX, gridY + i * cell, gridX + side, gridY + i * cell + 1, line);
		}
		// The middle square: where the screen's centre is.
		c.fill(gridX + middle * cell + 1, gridY + middle * cell + 1, gridX + (middle + 1) * cell, gridY + (middle + 1) * cell, 0xFF24272D);
		for (int y = 0; y < size; y++) {
			int run = -1;
			for (int x = 0; x <= size; x++) {
				boolean on = x < size && drawing.at(x, y);
				if (on && run < 0) {
					run = x;
				} else if (!on && run >= 0) {
					c.fill(gridX + run * cell + 1, gridY + y * cell + 1, gridX + x * cell, gridY + (y + 1) * cell, ink);
					run = -1;
				}
			}
		}
		// The square under the pointer, outlined in the accent.
		KitScreen screen = screen();
		if (screen != null) {
			int[] at = square(screen.mouseX(), screen.mouseY());
			if (at != null) {
				// The brush's squares, as a stroke there would cover them.
				int from = -(brushSize - 1) / 2;
				int bx = Math.max(0, at[0] + from);
				int by = Math.max(0, at[1] + from);
				int ex = Math.min(size, at[0] + from + brushSize);
				int ey = Math.min(size, at[1] + from + brushSize);
				c.ring(gridX + bx * cell, gridY + by * cell, (ex - bx) * cell + 1, (ey - by) * cell + 1, 0, 1, MenuStyle.ACCENT);
			}
		}
		MenuStyle.ring(c, (gridX - 1) / (float) c.scale(), (gridY - 1) / (float) c.scale(), (side + 2) / (float) c.scale(),
			(side + 2) / (float) c.scale(), 0, 1, MenuStyle.SECTION_EDGE);
		// The column beside it: the pen, the hints, the preview.
		float column = px + d(PAD) + gridUnits + d(32);
		float colW = px + pw - d(PAD) - column;
		// Lines a line of Minecraft's font apart, whatever its size against the layout's.
		float line = Math.max(d(20), 9F * MenuStyle.pixelScale() / c.scale() * 1.3F);
		MenuStyle.fitted(c, brushLabel, brush.x() + brush.width() + d(12), brush.y() + brush.height() / 2, MenuStyle.NEUTRAL,
			Math.max(d(20), colW - brush.width() - d(12)));
		float at = brush.y() + brush.height() + d(14) + line / 2;
		MenuStyle.fitted(c, hint, column, at, MenuStyle.NEUTRAL, colW);
		MenuStyle.fitted(c, hint2, column, at + line, MenuStyle.NEUTRAL, colW);
		MenuStyle.fitted(c, hint3, column, at + 2 * line, MenuStyle.NEUTRAL, colW);
		float labelY = at + 3 * line + d(10);
		MenuStyle.fitted(c, previewLabel, column, labelY, MenuStyle.TEXT, colW);
		float boxTop = labelY + line / 2 + d(6);
		float boxH = Math.max(d(60), Math.min(colW / 1.6F, done.y() - d(20) - boxTop));
		preview.paint(c, column, boxTop, colW, boxH);
		int n = drawing.count();
		count.set(n == 1 ? "1 square drawn" : n + " squares drawn");
		MenuStyle.fitted(c, count, px + d(PAD), py + ph - d(PAD) - d(10), MenuStyle.NEUTRAL, gridUnits);
	}

	/** The grid's own place, so the pointer on it reaches the pad (and a tour can find it by name). */
	private final class Grid extends Node {
		Grid() {
			name("Drawing grid");
		}

		@Override
		public boolean interactive() {
			return true;
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			return CrosshairPad.this.mouseDown(mx, my, button);
		}

		@Override
		public void mouseDrag(float mx, float my) {
			CrosshairPad.this.mouseDrag(mx, my);
		}

		@Override
		public void mouseUp(float mx, float my, int button) {
			CrosshairPad.this.mouseUp(mx, my, button);
		}
	}
}
