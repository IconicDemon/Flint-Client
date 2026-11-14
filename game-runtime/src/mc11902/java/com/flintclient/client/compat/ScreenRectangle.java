package com.flintclient.client.compat;

/** 1.19.4's screen rectangle ({@code net.minecraft.client.gui.navigation.ScreenRectangle}), for Minecraft 1.19.2: GUI units. */
public record ScreenRectangle(int left, int top, int width, int height) {
	public int right() {
		return left + width;
	}

	public int bottom() {
		return top + height;
	}

	public boolean containsPoint(int x, int y) {
		return x >= left && x < right() && y >= top && y < bottom();
	}

	public ScreenRectangle intersection(ScreenRectangle other) {
		int l = Math.max(left, other.left);
		int t = Math.max(top, other.top);
		int r = Math.min(right(), other.right());
		int b = Math.min(bottom(), other.bottom());
		return r <= l || b <= t ? new ScreenRectangle(0, 0, 0, 0) : new ScreenRectangle(l, t, r - l, b - t);
	}
}
