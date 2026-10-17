package com.flintclient.client.control.hud;

/** A rectangle in GUI units: top-left corner and size. */
public record HudRect(float x, float y, float width, float height) {
	public float right() {
		return x + width;
	}

	public float bottom() {
		return y + height;
	}

	public float centerX() {
		return x + width / 2.0F;
	}

	public float centerY() {
		return y + height / 2.0F;
	}

	/** Whether the two share any area; rectangles that only touch along an edge don't overlap. */
	public boolean overlaps(HudRect other) {
		return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
	}

	/** Whether this lies entirely within a screen of the given size. */
	public boolean within(float screenWidth, float screenHeight) {
		return x >= 0 && y >= 0 && right() <= screenWidth && bottom() <= screenHeight;
	}
}
