package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.Motion;

/**
 * Text tabs, left-aligned 12 units apart ({@link Type#LABEL}): the active one in the text colour with a 1.5-unit
 * ember underline exactly as wide as its text, the others muted (brighter on hover). The underline slides and
 * resizes between tabs in 160 ms (at once with reduced motion). Each tab is its own tab stop, named by its label.
 */
public class TextTabs extends Node {
	@FunctionalInterface
	public interface Listener {
		void selected(int index);
	}

	public static final float HEIGHT = 20;
	private static final float SLIDE_SECONDS = 0.16F;
	private final FontFace face;
	private final float gap;
	/** The text's centre line and the underline's top and thickness, from the tabs' top edge. */
	private final float textCenter;
	private final float underlineTop;
	private final float underline;
	private final float height;

	private final Tab[] tabs;
	private final Listener listener;
	private int selected;
	private float fromX = Float.NaN;
	private float fromW;
	private float toX;
	private float toW;
	private float progress = 1;
	private boolean placed;

	public TextTabs(String[] labels, int selected, Listener listener) {
		this(labels, selected, listener, Type.LABEL, HEIGHT, 12, 1.5F);
	}

	/**
	 * Tabs in {@code face}, {@code gap} units apart, {@code height} tall, with an underline {@code underline} units
	 * thick along their bottom edge (a little under the text).
	 */
	public TextTabs(String[] labels, int selected, Listener listener, FontFace face, float height, float gap, float underline) {
		this.face = face;
		this.gap = gap;
		this.height = height;
		this.underline = underline;
		this.underlineTop = height - underline - 1;
		this.textCenter = (height - underline - 1) / 2 - 0.5F;
		this.listener = listener;
		this.tabs = new Tab[labels.length];
		for (int i = 0; i < labels.length; i++) {
			tabs[i] = add(new Tab(this, i, labels[i]));
		}
		this.selected = selected;
		this.h = height;
	}

	public int selected() {
		return selected;
	}

	public Node tab(int index) {
		return tabs[index];
	}

	/** Makes {@code index} the active tab without telling the listener; the underline slides unless {@code instant}. */
	public void select(int index, boolean instant) {
		if (index == selected && placed) {
			return;
		}
		selected = index;
		startSlide(instant || Kit.reduced());
	}

	private void startSlide(boolean instant) {
		Tab tab = tabs[selected];
		float targetX = tab.x() - x;
		float targetW = tab.width();
		if (instant || Float.isNaN(fromX)) {
			fromX = targetX;
			fromW = targetW;
			progress = 1;
		} else {
			// From wherever the underline is now, so a quick second switch doesn't jump.
			fromX = currentX();
			fromW = currentW();
			progress = 0;
		}
		toX = targetX;
		toW = targetW;
	}

	private float eased() {
		return Motion.easeOut(progress);
	}

	private float currentX() {
		return fromX + (toX - fromX) * eased();
	}

	private float currentW() {
		return fromW + (toW - fromW) * eased();
	}

	@Override
	public float prefHeight(float width) {
		return height;
	}

	/** The tabs' natural width. */
	@Override
	public float prefWidth() {
		Canvas c = measure();
		float total = 0;
		for (Tab tab : tabs) {
			total += (float) c.width(tab.label) / c.scale();
		}
		return total + gap * Math.max(0, tabs.length - 1);
	}

	@Override
	protected void layout() {
		Canvas c = measure();
		float at = x;
		for (Tab tab : tabs) {
			float tw = (float) c.width(tab.label) / c.scale();
			tab.setBounds(at, y, tw, h);
			at += tw + gap;
		}
		Tab tab = tabs[selected];
		if (!placed) {
			placed = true;
			startSlide(true);
		} else {
			// Laid out again (a page changed size): a slide under way carries on to where its tab now is.
			toX = tab.x() - x;
			toW = tab.width();
			if (progress >= 1) {
				fromX = toX;
				fromW = toW;
			}
		}
	}

	@Override
	protected void paint(Canvas c) {
		if (progress < 1) {
			progress = Kit.reduced() ? 1 : Math.min(1, progress + Motion.frameSeconds() / SLIDE_SECONDS);
		}
	}

	@Override
	protected void paintOver(Canvas c) {
		float ux = x + currentX();
		int x0 = c.px(ux);
		int x1 = c.px(ux + currentW());
		int y0 = c.px(y + underlineTop);
		int y1 = c.px(y + underlineTop + underline);
		c.roundRectCorners(x0, y0, x1 - x0, y1 - y0, (y1 - y0) / 2, Tokens2.EMBER);
	}

	private void clicked(int index) {
		if (index != selected) {
			select(index, false);
			listener.selected(index);
		}
	}

	/** One tab: its label, pressable, a tab stop of its own. */
	private static final class Tab extends Pressable {
		private final TextTabs tabs;
		private final int index;
		private final TextLine label;

		Tab(TextTabs tabs, int index, String text) {
			super(null);
			this.tabs = tabs;
			this.index = index;
			this.label = new TextLine(tabs.face, text);
			name(text);
			setAction(() -> tabs.clicked(index));
		}

		@Override
		public String narration() {
			return withReason(label.text() + ", tab, " + (index + 1) + " of " + tabs.tabs.length + (tabs.selected == index ? ", selected" : ""));
		}

		@Override
		public void activate() {
			if (tabs.selected == index) {
				return;
			}
			super.activate();
		}

		@Override
		protected void paint(Canvas c) {
			boolean active = tabs.selected == index;
			int ink = active ? Tokens2.TEXT : Palette.lerp(Tokens2.muted(), Tokens2.TEXT_DIM, Math.max(hover, focus));
			int center = c.px(y + tabs.textCenter);
			c.text(label, px0(c), c.baselineFor(label, center), ink);
			Kit.focusRing(c, x - 3, y + 1, w + 6, tabs.underlineTop, 4, focus);
		}
	}
}
