package com.flintclient.client.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where each shape sits in the batched mask atlas and which rows of it still need uploading: the
 * bookkeeping half of {@link MaskAtlas}, kept free of GPU calls so it can be tested. Render thread only.
 *
 * <p>Shapes are shelf-packed left to right with a one-pixel gap. Adding a shape never moves the others:
 * when it doesn't fit, the atlas grows (the height doubles; the width only for a shape wider than the
 * atlas) and every shape keeps its place. Variable-size shapes (tooltips, cards) would otherwise pile up,
 * so the cache is bounded: once it holds {@link #MAX_SHAPES} shapes, or growing would pass
 * {@link #MAX_SIZE}, it is repacked from only the shapes drawn this frame and the one before. Those can
 * still need more room than the cap allows, so the bound is what one frame actually draws.
 */
final class AtlasLayout {
	static final int MAX_SHAPES = 512;
	static final int MAX_SIZE = 2048;
	static final int MIN_SIZE = 256;

	/** What adding a shape did to the texture. */
	enum Change {
		/** The shape went into free space; only its rows need uploading. */
		NONE,
		/** The atlas is bigger: a new texture, with every earlier shape at the same place. */
		GROWN,
		/** The atlas was rebuilt from recently drawn shapes: a new texture, and they moved (see {@link Slot#fromU}). */
		REPACKED
	}

	/** One packed shape. Mutable, so finding it only updates the frame it was last drawn in. */
	static final class Slot {
		final long key;
		final int width;
		final int height;
		int u;
		int v;
		/** Where the shape was before the last repack, to copy its pixels from; -1 for a shape new to it. */
		int fromU = -1;
		int fromV = -1;
		int usedFrame;
		/** What {@link MaskAtlas} hands out for it; replaced whenever the texture is. */
		MaskAtlas.@Nullable Entry entry;

		Slot(long key, int width, int height, int usedFrame) {
			this.key = key;
			this.width = width;
			this.height = height;
			this.usedFrame = usedFrame;
		}
	}

	private final Long2ObjectOpenHashMap<Slot> slots = new Long2ObjectOpenHashMap<>();
	private int width;
	private int height;
	// The shelf being filled: the next free x, its top, and its height so far.
	private int x = 1;
	private int y = 1;
	private int row;
	private int dirtyTop = Integer.MAX_VALUE;
	private int dirtyBottom;
	private int frame;

	/** Starts a frame: what's drawn from now on counts as recently used. */
	void nextFrame() {
		frame++;
	}

	/** The shape for {@code key}, marked as drawn this frame, or null when it isn't packed. Allocates nothing. */
	@Nullable Slot get(long key) {
		Slot slot = slots.get(key);
		if (slot != null) {
			slot.usedFrame = frame;
		}
		return slot;
	}

	/** Packs a new {@code w}×{@code h} shape (find it with {@link #get}) and says what that did to the texture. */
	Change add(long key, int w, int h) {
		Change change = Change.NONE;
		if (slots.size() >= MAX_SHAPES) {
			repack();
			change = Change.REPACKED;
		}
		if (!fits(w, h)) {
			if (change == Change.NONE && (grownWidth(w) > MAX_SIZE || grownHeight(grownWidth(w), w, h) > MAX_SIZE)) {
				repack();
				change = Change.REPACKED;
			}
			if (!fits(w, h)) {
				grow(w, h);
				if (change == Change.NONE) {
					change = Change.GROWN;
				}
			}
		}
		Slot slot = new Slot(key, w, h, frame);
		place(slot);
		slots.put(key, slot);
		if (change == Change.NONE) {
			// With the gap rows above and below: a new texture's rows past the used ones were never
			// uploaded, so they'd otherwise hold whatever the GPU memory held instead of transparency.
			dirtyTop = Math.min(dirtyTop, slot.v - 1);
			dirtyBottom = Math.max(dirtyBottom, slot.v + h + 1);
		} else {
			// A new texture: everything in use goes up with it.
			dirtyTop = 0;
			dirtyBottom = usedHeight();
		}
		return change;
	}

	/** Forgets every shape (a GUI scale change, or the menu's resources being released). */
	void clear() {
		slots.clear();
		width = 0;
		height = 0;
		x = 1;
		y = 1;
		row = 0;
		clean();
	}

	int width() {
		return width;
	}

	int height() {
		return height;
	}

	int size() {
		return slots.size();
	}

	Iterable<Slot> slots() {
		return slots.values();
	}

	/** Rows holding shapes, from the top (the rest of the texture has never been drawn from). */
	int usedHeight() {
		return Math.min(height, y + row + 1);
	}

	/** True when rows [{@link #dirtyTop}, {@link #dirtyBottom}) have changed since the last {@link #clean}. */
	boolean dirty() {
		return dirtyBottom > dirtyTop;
	}

	int dirtyTop() {
		return dirtyTop;
	}

	int dirtyBottom() {
		return dirtyBottom;
	}

	/** After uploading the dirty rows. */
	void clean() {
		dirtyTop = Integer.MAX_VALUE;
		dirtyBottom = 0;
	}

	/** True when a {@code w}×{@code h} shape fits in the free space without growing. */
	private boolean fits(int w, int h) {
		int top = wraps(w, width) ? y + row + 1 : y;
		int left = wraps(w, width) ? 1 : x;
		return left + w + 1 <= width && top + h + 1 <= height;
	}

	/** True when a {@code w}-wide shape has to start a new shelf in an atlas {@code atlasWidth} wide. */
	private boolean wraps(int w, int atlasWidth) {
		return x > 1 && x + w + 1 > atlasWidth;
	}

	private int grownWidth(int w) {
		int grown = Math.max(width, MIN_SIZE);
		while (grown < w + 2) {
			grown *= 2;
		}
		return grown;
	}

	private int grownHeight(int atlasWidth, int w, int h) {
		int top = wraps(w, atlasWidth) ? y + row + 1 : y;
		int grown = Math.max(height, MIN_SIZE);
		while (grown < top + h + 1) {
			grown *= 2;
		}
		return grown;
	}

	private void grow(int w, int h) {
		int grownWidth = grownWidth(w);
		height = grownHeight(grownWidth, w, h);
		width = grownWidth;
	}

	/** Puts a shape at the shelf cursor (which {@link #fits} has checked) and moves the cursor past it. */
	private void place(Slot slot) {
		if (wraps(slot.width, width)) {
			x = 1;
			y += row + 1;
			row = 0;
		}
		slot.u = x;
		slot.v = y;
		x += slot.width + 1;
		row = Math.max(row, slot.height);
	}

	/** Keeps only the shapes drawn this frame or the last, packed afresh (tallest first, for tight shelves). */
	private void repack() {
		List<Slot> kept = new ArrayList<>();
		for (Slot slot : slots.values()) {
			if (slot.usedFrame >= frame - 1) {
				slot.fromU = slot.u;
				slot.fromV = slot.v;
				kept.add(slot);
			}
		}
		kept.sort(Comparator.comparingInt((Slot slot) -> slot.height).reversed());
		slots.clear();
		width = 0;
		height = 0;
		x = 1;
		y = 1;
		row = 0;
		for (Slot slot : kept) {
			if (!fits(slot.width, slot.height)) {
				grow(slot.width, slot.height);
			}
			place(slot);
			slots.put(slot.key, slot);
		}
	}
}
