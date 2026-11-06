package com.flintclient.client.control.feature;

/**
 * Which mouse buttons are held, every button, whatever screen is open: fed from the first thing Minecraft's mouse
 * handler does with a button event on the game window ({@code MouseHandlerMixin}), before a screen or an overlay can
 * take the event. Minecraft keeps a state only for left, middle and right, and only while no screen is open, so a
 * Flint key bound to a side button (mouse 4 or 5) was never seen held, and one let go in a screen stayed held after it.
 * Buttons are the version's own numbers (0-based on GLFW, 1-based on SDL). Render thread; allocates nothing.
 */
public final class MouseButtons {
	/** More buttons than any mouse sends; numbers outside are ignored. */
	static final int COUNT = 32;
	private static final boolean[] DOWN = new boolean[COUNT];

	private MouseButtons() {
	}

	/** A button event on the game window: {@code pressed} for a press, false for a release. */
	public static void event(int button, boolean pressed) {
		if (button >= 0 && button < COUNT) {
			DOWN[button] = pressed;
		}
	}

	/** Whether {@code button} is held. */
	public static boolean down(int button) {
		return button >= 0 && button < COUNT && DOWN[button];
	}

	/** Lets go of every button (the window lost the focus: the releases may never arrive). */
	public static void releaseAll() {
		java.util.Arrays.fill(DOWN, false);
	}
}
