package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.TextLine;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A section header: small tracked capitals in the muted colour ({@link Type#OVERLINE}), with 12 units of space
 * above and 4 below, and optionally an action at its right ("+ New layout"). Sections are separated by this
 * space and the header's type, not by boxes.
 */
public class Overline extends Node {
	/** Space above the text (the section break). */
	public static final float ABOVE = 12;
	private static final float LINE = 6;
	private static final float BELOW = 4;

	private final TextLine text;
	private final float above;
	private @Nullable Node action;

	public Overline(String text) {
		this(text, ABOVE);
	}

	/** A header with {@code above} units of space over it (0 for the first thing on a page). */
	public Overline(String text, float above) {
		this.text = new TextLine(Type.OVERLINE, text.toUpperCase(Locale.ROOT), Type.OVERLINE_TRACKING);
		this.above = above;
		this.h = above + LINE + BELOW;
	}

	/** Puts {@code node} at the right end of the header, centred on its text. */
	public Overline action(Node node) {
		if (action != null) {
			remove(action);
		}
		action = add(node);
		invalidate();
		return this;
	}

	public void setText(String value) {
		text.set(value.toUpperCase(Locale.ROOT));
	}

	@Override
	public float prefHeight(float width) {
		return above + LINE + BELOW;
	}

	@Override
	protected void layout() {
		Node node = action;
		if (node != null) {
			float aw = node.prefWidth();
			float ah = node.prefHeight(aw);
			node.setBounds(x + w - aw, y + above + LINE / 2 - ah / 2, aw, ah);
		}
	}

	@Override
	protected void paint(Canvas c) {
		int center = c.px(y + above + LINE / 2);
		int max = action != null ? c.px(action.x() - 6) - px0(c) : pxW(c);
		c.text(text, px0(c), c.baselineFor(text, center), Tokens2.muted(), Math.max(0, max));
	}
}
