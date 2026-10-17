package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.hud.TextOut;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.render.Palette;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Item icons each with a short text (a count, seconds left), in a row or a column, on the shared HUD panel: the Item
 * Counter and Cooldowns. Subclasses fill the entries ({@link #collect}); text is rebuilt only when it changes, at most
 * every {@link #REFRESH_NANOS}. A share below 1 (cooldowns) draws a thin bar under the icon.
 */
abstract class ItemListView extends ModuleView {
	static final int MAX = 12;
	private static final long REFRESH_NANOS = 100_000_000L;

	final @Nullable ItemStack[] stacks = new ItemStack[MAX];
	final float[] share = new float[MAX];
	private final HudText[] texts = new HudText[MAX];
	private final String[] shown = new String[MAX];
	private final char[] buffer = new char[32];
	final TextOut o = new TextOut();
	private final int[] textW = new int[MAX];
	private int count;
	private long collectedAt;
	private int collectedMode = -1;
	private int collectedRevision = -1;
	private boolean vertical;
	private int itemPx;
	private int gap;

	ItemListView(String moduleId, int surface) {
		super(moduleId, surface);
		for (int i = 0; i < MAX; i++) {
			texts[i] = new HudText("");
		}
	}

	/**
	 * Fills {@link #stacks} and {@link #share} (1 = no bar) and writes each entry's text through {@link #setText}; returns
	 * how many entries there are. SAMPLE mode shows fixed sample entries. Must not allocate in steady state.
	 */
	abstract int collect(ModuleState state, int mode, long nowNanos);

	abstract boolean vertical(ModuleState state);

	/** Sets entry {@code i}'s text to what {@link #o} holds (started on {@link #text()}). */
	final void setText(int i) {
		int length = o.length();
		String last = shown[i];
		boolean same = last != null && last.length() == length;
		for (int k = 0; same && k < length; k++) {
			same = last.charAt(k) == buffer[k];
		}
		if (!same) {
			String text = new String(buffer, 0, length);
			shown[i] = text;
			texts[i].set(text);
		}
	}

	/** {@link #o}, cleared for the next entry's text. */
	final TextOut text() {
		return o.start(buffer);
	}

	@Override
	final void update(ModuleState state, int mode, long nowNanos) {
		vertical = vertical(state);
		if (mode == collectedMode && state.revision() == collectedRevision && nowNanos - collectedAt < REFRESH_NANOS) {
			return;
		}
		collectedMode = mode;
		collectedRevision = state.revision();
		collectedAt = nowNanos;
		java.util.Arrays.fill(share, 1);
		count = mode == PLACEHOLDER ? 0 : Math.min(MAX, collect(state, mode, nowNanos));
	}

	@Override
	final void measure(HudPen p, ModuleState state) {
		HudStyle s = p.s;
		itemPx = Math.max(8, s.px(16));
		gap = Math.max(1, s.px(2));
		if (count == 0) {
			width = 0;
			height = 0;
			return;
		}
		int w = 0;
		int h = 0;
		for (int i = 0; i < count; i++) {
			textW[i] = p.width(texts[i], HudFaces.VALUE);
			int entryW = itemPx + gap + textW[i];
			if (vertical) {
				w = Math.max(w, entryW);
				h += itemPx + (i > 0 ? gap : 0);
			} else {
				w += entryW + (i > 0 ? s.px(5) : 0);
				h = itemPx;
			}
		}
		width = w;
		height = h;
	}

	@Override
	final void draw(HudPen p, ModuleState state, int x, int y) {
		HudStyle s = p.s;
		int at = x;
		int top = y;
		for (int i = 0; i < count; i++) {
			ItemStack stack = stacks[i];
			if (stack != null) {
				p.item(stack, at, top, itemPx, null);
			}
			if (share[i] < 1) {
				int barH = Math.max(1, s.px(1.5F));
				int barW = Math.round(itemPx * Math.clamp(share[i], 0, 1));
				p.fill(at, top + itemPx - barH, at + itemPx, top + itemPx, 0x80000000);
				p.fill(at, top + itemPx - barH, at + barW, top + itemPx, Palette.withAlpha(s.text, 0.9F));
			}
			int baseline = p.baseline(top + (itemPx - s.lineH) / 2);
			p.text(texts[i], HudFaces.VALUE, at + itemPx + gap, baseline, s.text);
			if (vertical) {
				top += itemPx + gap;
			} else {
				at += itemPx + gap + textW[i] + s.px(5);
			}
		}
	}

	@Override
	void forget() {
		java.util.Arrays.fill(stacks, null);
		count = 0;
	}

	@Override
	String emptyReason() {
		return "Nothing to show right now";
	}
}
