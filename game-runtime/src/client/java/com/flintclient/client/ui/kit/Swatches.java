package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * A row of 11-unit colour circles, 5 units apart. The chosen one has a one-pixel ring in the text colour 1.5 units
 * out (a shape, not colour alone), and the narrator says which is chosen. A colour of {@code 0} is "Default": a
 * split circle (half the text colour, half dark), meaning "whatever the style uses". Each circle's tooltip names
 * its colour. One tab stop: Left and Right choose.
 */
public class Swatches extends Node {
	@FunctionalInterface
	public interface Listener {
		void changed(int index);
	}

	public static final float SIZE = 11;
	private static final float GAP = 5;
	/** Room around the circles for the selection ring (1.5 units out, plus its pixel). */
	private static final float MARGIN = 2;
	private static final int DEFAULT_DARK = 0xFF2A2724;

	private final int[] colors;
	private final String[] names;
	private final TextLine[][] tips;
	private final @Nullable Listener listener;
	private int selected;
	/** The light half of the "Default" circle (the style's own text colour). */
	private int defaultLight = Tokens2.TEXT;

	public Swatches(int[] colors, String[] names, int selected, @Nullable Listener listener) {
		if (colors.length != names.length) {
			throw new IllegalArgumentException("A name for every colour");
		}
		this.colors = colors.clone();
		this.names = names.clone();
		this.tips = new TextLine[names.length][];
		for (int i = 0; i < names.length; i++) {
			tips[i] = lines(names[i]);
		}
		this.selected = Math.clamp(selected, 0, Math.max(0, colors.length - 1));
		this.listener = listener;
		this.h = SIZE + 2 * MARGIN;
	}

	public int selected() {
		return selected;
	}

	public void setSelected(int index) {
		selected = Math.clamp(index, 0, Math.max(0, colors.length - 1));
	}

	/** The colour the "Default" circle's light half shows (the style's own text colour). */
	public void setDefaultColor(int argb) {
		defaultLight = argb | 0xFF000000;
	}

	@Override
	public boolean interactive() {
		return true;
	}

	@Override
	public boolean focusable() {
		return colors.length > 0;
	}

	@Override
	public float prefWidth() {
		return colors.length * SIZE + Math.max(0, colors.length - 1) * GAP + 2 * MARGIN;
	}

	@Override
	public float prefHeight(float width) {
		return SIZE + 2 * MARGIN;
	}

	/** Right-aligned in the bounds (rows give it their whole control area). */
	private float swatchX(int i) {
		return x + w - prefWidth() + MARGIN + i * (SIZE + GAP);
	}

	private float swatchY() {
		return y + (h - SIZE) / 2;
	}

	private int swatchAt(float mx) {
		for (int i = 0; i < colors.length; i++) {
			float left = swatchX(i) - GAP / 2;
			if (mx >= left && mx < left + SIZE + GAP) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public TextLine @Nullable [] tooltipLines() {
		if (!enabled() && disabledReason() != null) {
			return super.tooltipLines();
		}
		if (colors.length == 0) {
			return null;
		}
		int index = hovered() && screen != null ? swatchAt(screen.mouseX()) : selected;
		return index >= 0 ? tips[index] : null;
	}

	@Override
	protected void tooltipAnchor(float[] out) {
		int index = hovered() && screen != null ? swatchAt(screen.mouseX()) : selected;
		out[0] = swatchX(Math.max(0, index));
		out[1] = swatchY();
		out[2] = SIZE;
		out[3] = SIZE;
	}

	@Override
	public String narration() {
		String name = accessibleName();
		String choice = colors.length == 0 ? "" : names[selected] + ", selected, " + (selected + 1) + " of " + colors.length;
		return withReason(name.isEmpty() ? choice : name + ", " + choice);
	}

	private void choose(int index) {
		if (colors.length == 0) {
			return;
		}
		int next = Math.clamp(index, 0, colors.length - 1);
		if (next == selected) {
			return;
		}
		selected = next;
		if (screen != null) {
			screen.playClick();
			if (focused()) {
				screen.announce(this);
			}
		}
		if (listener != null) {
			listener.changed(selected);
		}
	}

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		int index = swatchAt(mx);
		if (button == Kit.LEFT_BUTTON && index >= 0) {
			choose(index);
		}
		return false;
	}

	@Override
	public boolean keyDown(KeyEvent event) {
		if (event.isLeft()) {
			choose(selected - 1);
		} else if (event.isRight()) {
			choose(selected + 1);
		} else if (event.key() == InputConstants.KEY_HOME) {
			choose(0);
		} else if (event.key() == InputConstants.KEY_END) {
			choose(colors.length - 1);
		} else {
			return event.isSelection();
		}
		return true;
	}

	@Override
	protected void paint(Canvas c) {
		int hoverIndex = hovered() && screen != null ? swatchAt(screen.mouseX()) : -1;
		int size = c.px(SIZE);
		float alpha = enabled() ? 1 : 0.4F;
		for (int i = 0; i < colors.length; i++) {
			int sx = c.px(swatchX(i));
			int sy = c.px(swatchY());
			int r = size / 2;
			if (colors[i] == 0) {
				// "Default": the left half light, the right half dark, each two quarter circles.
				int light = Palette.withAlpha(defaultLight, alpha);
				int dark = Palette.withAlpha(DEFAULT_DARK, alpha);
				// Quarters of half the size rounded up: at an odd size the halves share the middle column.
				int q = (size + 1) / 2;
				c.quarter(sx, sy, q, Canvas.TOP_LEFT, light);
				c.quarter(sx, sy + size - q, q, Canvas.BOTTOM_LEFT, light);
				c.quarter(sx + size - q, sy, q, Canvas.TOP_RIGHT, dark);
				c.quarter(sx + size - q, sy + size - q, q, Canvas.BOTTOM_RIGHT, dark);
			} else {
				c.roundRect(sx, sy, size, size, r, Palette.withAlpha(colors[i] | 0xFF000000, alpha));
			}
			// A faint edge keeps dark colours visible on the dark deck.
			c.ring(sx, sy, size, size, r, 1, i == hoverIndex ? 0x30FFFFFF : 0x14FFFFFF);
			if (i == selected) {
				int gap = c.px(1.5F);
				int ring = size + 2 * gap + 2;
				c.ring(sx - gap - 1, sy - gap - 1, ring, ring, ring / 2, Kit.prefs().highContrast() ? 2 : 1, Palette.withAlpha(Tokens2.TEXT, alpha));
			}
		}
		if (colors.length > 0) {
			Kit.focusRing(c, swatchX(selected) - 2, swatchY() - 2, SIZE + 4, SIZE + 4, SIZE / 2 + 2, focus);
		}
	}
}
