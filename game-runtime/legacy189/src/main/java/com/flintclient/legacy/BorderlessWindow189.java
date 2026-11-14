package com.flintclient.legacy;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * Flint's Borderless fullscreen on 1.8.9 (the shared switch, on by default): Minecraft's Fullscreen (F11) becomes an undecorated
 * LWJGL 2 window over the whole monitor the game is on, one pixel wider than it (as Minecraft 26.x does, so Windows doesn't
 * promote it to exclusive presentation), at the desktop's resolution: no display mode change, instant Alt+Tab, and a screen
 * capture (Discord, OBS) sees every frame. Off: LWJGL 2's own fullscreen, unchanged. The window's place and size before are
 * kept and put back. Windows only (the monitor's bounds come from user32 through JNA); elsewhere the switch changes nothing.
 * Render thread only.
 */
public final class BorderlessWindow189 {
	private static final Logger LOG = LogManager.getLogger("Flint");
	private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
	private static final String UNDECORATED = "org.lwjgl.opengl.Window.undecorated";
	private static boolean active;
	/** Minecraft started in fullscreen: the borderless window is made once the display exists. */
	private static boolean pending;
	private static int windowedX = -1;
	private static int windowedY = -1;

	private BorderlessWindow189() {
	}

	/** Whether Fullscreen means Flint's borderless window now. */
	public static boolean wanted() {
		return WINDOWS && ControlState.settings().gameBool(GameOptions.BORDERLESS_FULLSCREEN);
	}

	/** In place of {@code Display.setFullscreen(fullscreen)} when Minecraft switches (F11, Video Settings). */
	public static void setFullscreen(boolean fullscreen) throws LWJGLException {
		if (fullscreen && wanted()) {
			enter();
			return;
		}
		if (active) {
			leave();
		}
		Display.setFullscreen(fullscreen);
	}

	/** In place of {@code Display.setFullscreen(true)} before the display exists: windowed for now, borderless at the first tick. */
	public static void atStart(boolean fullscreen) throws LWJGLException {
		if (fullscreen && wanted()) {
			pending = true;
			return;
		}
		Display.setFullscreen(fullscreen);
	}

	/** Each client tick: the start-up borderless window, once the display exists; true when it was just made (resize the game). */
	public static boolean tick() {
		if (pending && Display.isCreated()) {
			pending = false;
			try {
				enter();
				return true;
			} catch (LWJGLException | RuntimeException | LinkageError e) {
				LOG.warn("Flint: couldn't make the borderless fullscreen window", e);
			}
		}
		return false;
	}

	/** Whether the borderless window is up (Minecraft then skips its own fullscreen display mode). */
	public static boolean active() {
		return active;
	}

	private static void enter() throws LWJGLException {
		int[] bounds = monitorBounds(Display.getX() + Display.getWidth() / 2, Display.getY() + Display.getHeight() / 2);
		if (bounds == null) {
			Display.setFullscreen(true);
			return;
		}
		if (Display.isFullscreen()) {
			Display.setFullscreen(false);
		}
		if (!active) {
			windowedX = Display.getX();
			windowedY = Display.getY();
		}
		System.setProperty(UNDECORATED, "true");
		Display.setLocation(bounds[0], bounds[1]);
		Display.setDisplayMode(new DisplayMode(bounds[2] - bounds[0] + 1, bounds[3] - bounds[1]));
		Display.setLocation(bounds[0], bounds[1]);
		active = true;
	}

	private static void leave() throws LWJGLException {
		active = false;
		System.clearProperty(UNDECORATED);
		DisplayMode mode = Display.getDisplayMode();
		Display.setDisplayMode(new DisplayMode(mode.getWidth(), mode.getHeight()));
		if (windowedX != -1 || windowedY != -1) {
			Display.setLocation(windowedX, windowedY);
		}
	}

	/** The bounds (left, top, right, bottom) of the monitor nearest to a point, from user32; null where that can't be asked. */
	private static int[] monitorBounds(int x, int y) {
		try {
			Function monitorFromPoint = Function.getFunction("user32", "MonitorFromPoint");
			Function getMonitorInfo = Function.getFunction("user32", "GetMonitorInfoW");
			// POINT by value is one 64-bit argument on x64; MONITOR_DEFAULTTONEAREST.
			long point = (x & 0xFFFFFFFFL) | ((long) y << 32);
			Pointer monitor = monitorFromPoint.invokePointer(new Object[] {point, 2});
			if (monitor == null) {
				return null;
			}
			Memory info = new Memory(40);
			info.setInt(0, 40);
			if (getMonitorInfo.invokeInt(new Object[] {monitor, info}) == 0) {
				return null;
			}
			return new int[] {info.getInt(4), info.getInt(8), info.getInt(12), info.getInt(16)};
		} catch (Throwable e) {
			LOG.warn("Flint: couldn't read the monitor's bounds; using Minecraft's fullscreen", e);
			return null;
		}
	}
}
