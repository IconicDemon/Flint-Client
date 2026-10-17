package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.Motion;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * A vertical scroll area around one content node. The wheel moves a target that the view eases towards
 * with real frame time (instant when motion is reduced); a thin 3-unit thumb appears while the pointer is
 * over the view or it scrolls, and can be dragged. The position is kept when the content changes size
 * (only clamped), the content is clipped to the view, and a child focused from the keyboard is scrolled
 * into view. Offsets are applied in whole pixels, so everything inside moves together without shimmer.
 *
 * <p>The content is always laid out {@link #GUTTER} narrower than the view: the bar never covers a control at
 * its right edge, a press in that strip always reaches the bar, and nothing reflows (a grid losing a column,
 * say) when the content grows long enough to scroll. Pages make the view {@code GUTTER} wider than their
 * content column, so rows line up with the page header either way.
 */
public class ScrollView extends Node {
	private static final float BAR = 3;
	private static final float BAR_INSET = 1.5F;
	/** The strip at the right kept for the bar (units). */
	public static final float GUTTER = BAR + 2 * BAR_INSET;
	private static final float MIN_THUMB = 16;
	/** Units per wheel notch. */
	private static final float WHEEL = 28;
	private static final float EASE_TAU = 0.075F;
	private static final long BAR_LINGER_MILLIS = 900;

	private final ScrollModel model = new ScrollModel();
	private final Node content;
	/** The offset currently applied to the content's position (snapped to whole pixels). */
	private float applied;
	private float barAlpha;
	private long scrolledAt;
	private boolean draggingThumb;
	private float grabOffset;
	/**
	 * A fixed-colour thumb (0: the kit's own white, shown only around scrolling); with one, the bar shows whenever the content
	 * overflows, the gutter is kept only then, and the wheel moves {@link #flatWheel} units per notch.
	 */
	private int flatThumb;
	private float flatWheel = WHEEL;
	private float flatGutter;
	/** The surface colour the content fades into at an edge with more beyond it (0: no fade). */
	private int fadeColor;
	private float fadeUnits;

	public ScrollView(Node content) {
		this.content = add(content);
	}

	public Node content() {
		return content;
	}

	/**
	 * Fades the content into {@code surface} over {@code units} at the top and bottom edges while there is more to
	 * scroll that way, so a list ends softly instead of being cut by the view's edge.
	 */
	public ScrollView fadeEdges(int surface, float units) {
		this.fadeColor = surface;
		this.fadeUnits = units;
		return this;
	}

	/**
	 * The Control Center's scroll bar: a {@code barWidth}-wide thumb in {@code thumb} {@code inset} units from the right edge,
	 * shown only while there is overflow, {@code gutter} units kept for it only then, {@code wheel} units per wheel notch.
	 */
	public ScrollView flatBar(int thumb, float gutter, float wheel) {
		this.flatThumb = thumb;
		this.flatGutter = gutter;
		this.flatWheel = wheel;
		return this;
	}

	public ScrollModel model() {
		return model;
	}

	/** Jumps back to the top (a new page or search). */
	public void scrollToTop() {
		model.scrollTo(0, true);
		invalidate();
	}

	@Override
	protected boolean clips() {
		return true;
	}

	@Override
	public boolean interactive() {
		return true;
	}

	@Override
	protected void layout() {
		float contentWidth = Math.max(0, w - (flatThumb != 0 ? 0 : GUTTER));
		float contentHeight = content.prefHeight(contentWidth);
		if (flatThumb != 0 && contentHeight > h + 0.5F) {
			// Overflow: keep the gutter for the bar and measure again at the narrower width.
			contentWidth = Math.max(0, w - flatGutter);
			contentHeight = content.prefHeight(contentWidth);
		}
		model.setSizes(contentHeight, h);
		applied = snap(model.offset());
		content.setBounds(x, y - applied, contentWidth, contentHeight);
	}

	/** {@code offset} rounded to whole pixels at the screen's scale. */
	private float snap(float offset) {
		int scale = screen != null ? screen.uiScale() : 1;
		return Math.round(offset * scale) / (float) scale;
	}

	@Override
	public boolean scroll(float mx, float my, double amount) {
		if (!model.scrollable()) {
			return false;
		}
		model.scrollBy((float) (-amount * (flatThumb != 0 ? flatWheel : WHEEL)));
		scrolledAt = Util.getMillis();
		return true;
	}

	@Override
	protected void reveal(Node descendant) {
		if (!content.contains(descendant) || descendant == content) {
			return;
		}
		float top = descendant.y() - content.y();
		float before = model.target();
		model.ensureVisible(top, top + descendant.height(), 6);
		if (model.target() != before) {
			scrolledAt = Util.getMillis();
		}
	}

	// ---------- thumb ----------

	private float trackTop() {
		return y + BAR_INSET;
	}

	private float trackLength() {
		return h - 2 * BAR_INSET;
	}

	private boolean onBar(float mx) {
		return model.scrollable() && mx >= x + w - GUTTER;
	}

	/** The bar's strip is the view's own, whatever the content puts under it. */
	@Override
	@Nullable Node hit(float px, float py) {
		if (visible() && containsPoint(px, py) && onBar(px)) {
			return this;
		}
		return super.hit(px, py);
	}

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		if (button != Kit.LEFT_BUTTON || !onBar(mx)) {
			return false;
		}
		float thumbTop = trackTop() + model.thumbPos(trackLength(), MIN_THUMB);
		float thumbSize = model.thumbSize(trackLength(), MIN_THUMB);
		// Grabbing the thumb keeps the grab point; clicking the track centres the thumb there.
		grabOffset = my >= thumbTop && my < thumbTop + thumbSize ? my - thumbTop : thumbSize / 2;
		draggingThumb = true;
		mouseDrag(mx, my);
		return true;
	}

	@Override
	public void mouseDrag(float mx, float my) {
		if (draggingThumb) {
			model.scrollTo(model.offsetForThumb(my - trackTop() - grabOffset, trackLength(), MIN_THUMB), true);
			scrolledAt = Util.getMillis();
		}
	}

	@Override
	public void mouseUp(float mx, float my, int button) {
		draggingThumb = false;
	}

	// ---------- drawing ----------

	@Override
	protected void paintChildren(Canvas c) {
		float k = Kit.reduced() ? 1 : 1 - (float) Math.exp(-Motion.frameSeconds() / EASE_TAU);
		model.step(k);
		float next = snap(model.offset());
		if (next != applied) {
			content.offset(0, applied - next);
			applied = next;
		}
		if (flatThumb != 0 && model.maxOffset() <= 0 && applied == 0) {
			// Nothing to scroll: no clip either (a scissor is rounded inwards to whole GUI units, which would shave the
			// content's edges at no gain).
			super.paintChildren(c);
			return;
		}
		int x0 = px0(c);
		int y0 = py0(c);
		c.pushClip(x0, y0, x0 + pxW(c), y0 + pxH(c));
		try {
			super.paintChildren(c);
		} finally {
			c.popClip();
		}
	}

	@Override
	protected void paintOver(Canvas c) {
		if (!model.scrollable()) {
			barAlpha = 0;
			return;
		}
		if (fadeColor != 0) {
			int x0 = px0(c);
			int x1 = c.px(x + w - GUTTER);
			int fade = c.px(fadeUnits);
			float top = Math.min(1, model.offset() / Math.max(1, fadeUnits));
			float bottom = Math.min(1, (model.maxOffset() - model.offset()) / Math.max(1, fadeUnits));
			int clear = fadeColor & 0x00FFFFFF;
			if (top > 0.01F) {
				c.gradient(x0, py0(c), x1, py0(c) + fade, Palette.withAlpha(fadeColor, top), clear);
			}
			if (bottom > 0.01F) {
				int y1 = py0(c) + pxH(c);
				c.gradient(x0, y1 - fade, x1, y1, clear, Palette.withAlpha(fadeColor, bottom));
			}
		}
		boolean pointerIn = screen != null && containsPoint(screen.mouseX(), screen.mouseY());
		if (flatThumb != 0) {
			float size = model.thumbSize(trackLength(), MIN_THUMB);
			float top = trackTop() + model.thumbPos(trackLength(), MIN_THUMB);
			float left = x + w - BAR - 2;
			int tx = c.px(left);
			int ty = c.px(top);
			int tw = c.px(left + BAR) - tx;
			int th = c.px(top + size) - ty;
			c.roundRectCorners(tx, ty, tw, th, c.px(1.5F), flatThumb);
			return;
		}
		boolean recent = Util.getMillis() - scrolledAt < BAR_LINGER_MILLIS;
		barAlpha = Kit.approach(barAlpha, pointerIn || recent || draggingThumb ? 1 : 0, 0.12F);
		if (barAlpha <= 0.01F) {
			return;
		}
		float size = model.thumbSize(trackLength(), MIN_THUMB);
		float top = trackTop() + model.thumbPos(trackLength(), MIN_THUMB);
		float left = x + w - BAR - BAR_INSET;
		int tx = c.px(left);
		int ty = c.px(top);
		int tw = c.px(left + BAR) - tx;
		int th = c.px(top + size) - ty;
		boolean overBar = pointerIn && screen != null && onBar(screen.mouseX());
		float strength = draggingThumb ? 0.55F : overBar ? 0.45F : 0.28F;
		// Corners and fills: the thumb's length follows the content, which mustn't add an atlas mask per length.
		c.roundRectCorners(tx, ty, tw, th, tw / 2, Palette.withAlpha(Tokens.TEXT, strength * barAlpha));
	}
}
