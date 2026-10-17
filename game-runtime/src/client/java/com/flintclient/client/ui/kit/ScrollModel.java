package com.flintclient.client.ui.kit;

/**
 * A vertical scroll position, kept pure for tests. The wheel and keyboard move a target; the shown
 * offset eases towards it ({@link #step}); both always stay within [0, content − viewport], also when the
 * content or viewport changes size (the offset is kept, only clamped).
 */
public final class ScrollModel {
	private float content;
	private float viewport;
	private float target;
	private float offset;

	public void setSizes(float contentHeight, float viewportHeight) {
		content = Math.max(0.0F, contentHeight);
		viewport = Math.max(0.0F, viewportHeight);
		target = clamp(target);
		offset = clamp(offset);
	}

	public float contentHeight() {
		return content;
	}

	public float viewportHeight() {
		return viewport;
	}

	public float maxOffset() {
		return Math.max(0.0F, content - viewport);
	}

	public boolean scrollable() {
		return content > viewport + 0.01F;
	}

	public float offset() {
		return offset;
	}

	public float target() {
		return target;
	}

	private float clamp(float value) {
		return Math.clamp(value, 0.0F, maxOffset());
	}

	public void scrollBy(float delta) {
		target = clamp(target + delta);
	}

	/** Sets the target; {@code instant} also jumps the shown offset there. */
	public void scrollTo(float position, boolean instant) {
		target = clamp(position);
		if (instant) {
			offset = target;
		}
	}

	/** Moves the target the least needed so [top, bottom] (content coordinates) is in view with {@code margin} around it. */
	public void ensureVisible(float top, float bottom, float margin) {
		if (top - margin < target) {
			target = clamp(top - margin);
		} else if (bottom + margin > target + viewport) {
			target = clamp(Math.min(top - margin, bottom + margin - viewport));
		}
	}

	/** Eases the offset towards the target by fraction {@code k} (1 = jump); true while it is still moving. */
	public boolean step(float k) {
		float next = offset + (target - offset) * Math.clamp(k, 0.0F, 1.0F);
		offset = Math.abs(target - next) < 0.05F ? target : next;
		return offset != target;
	}

	/** The thumb's length along a track, at least {@code minThumb} (the whole track when nothing scrolls). */
	public float thumbSize(float track, float minThumb) {
		if (!scrollable() || content <= 0.0F) {
			return track;
		}
		return Math.clamp(track * viewport / content, Math.min(minThumb, track), track);
	}

	/** The thumb's position along the track for the current offset. */
	public float thumbPos(float track, float minThumb) {
		float max = maxOffset();
		return max <= 0.0F ? 0.0F : (track - thumbSize(track, minThumb)) * offset / max;
	}

	/** The offset that puts the thumb at {@code thumbPos} (dragging it). */
	public float offsetForThumb(float thumbPos, float track, float minThumb) {
		float room = track - thumbSize(track, minThumb);
		return room <= 0.0F ? 0.0F : clamp(thumbPos / room * maxOffset());
	}
}
