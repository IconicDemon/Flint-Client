package com.flintclient.legacy.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.sun.jna.Function;
import com.sun.jna.Pointer;
import java.awt.Canvas;
import java.awt.Frame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * Instant Swap's hidden window on 1.8.9 (LWJGL 2 has no hidden-window flag, and its window shows itself and asks for the front
 * when made): a standby game's display is made inside a canvas of an AWT frame that is never shown (LWJGL 2's
 * {@code Display.setParent}), so it draws into a window nobody sees, never takes the screen, the front or a display mode, and
 * stays windowed when the player plays fullscreen. On the takeover the display leaves the canvas ({@code setParent(null)}:
 * LWJGL makes its own top-level window, the GL context and everything in it kept, as Minecraft's own fullscreen switch does)
 * in the old window's place and size, shows in front, and the frame goes. Render thread.
 */
public final class SwapWindow189 {
	private static final Logger LOG = LogManager.getLogger("Flint");
	private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
	private static @Nullable Frame frame;
	private static @Nullable Canvas canvas;

	private SwapWindow189() {
	}

	/** Before Minecraft makes its display: a standby game's goes into a hidden canvas of {@code width} x {@code height}. */
	public static void beforeCreate(int width, int height) {
		if (!InstantSwap.standby() || Display.isCreated()) {
			return;
		}
		try {
			Frame f = new Frame("Flint standby");
			f.setUndecorated(true);
			f.setLayout(null);
			f.setFocusableWindowState(false);
			Canvas c = new Canvas();
			c.setBounds(0, 0, Math.max(1, width), Math.max(1, height));
			f.add(c);
			f.setSize(Math.max(1, width), Math.max(1, height));
			// The native window (and the canvas's) without showing it.
			f.addNotify();
			Display.setParent(c);
			frame = f;
			canvas = c;
			LOG.info("Flint: Instant Swap: the standby game's window is hidden ({}x{})", width, height);
		} catch (LWJGLException | RuntimeException | LinkageError e) {
			LOG.warn("Flint: Instant Swap: couldn't hide the standby window", e);
			frame = null;
			canvas = null;
		}
	}

	/** Whether the display is in the hidden canvas now. */
	public static boolean hidden() {
		return canvas != null;
	}

	/**
	 * The display leaves the hidden canvas: a top-level window of {@code width} x {@code height} at ({@code x}, {@code y}), shown
	 * in front. False when it couldn't (it then shows as it is, if it can).
	 */
	public static boolean show(int x, int y, int width, int height) {
		Canvas c = canvas;
		Frame f = frame;
		if (c == null) {
			return true;
		}
		try {
			if (width > 0 && height > 0) {
				c.setBounds(0, 0, width, height);
				Display.setDisplayMode(new DisplayMode(width, height));
			}
			Display.setLocation(x, y);
			Display.setParent(null);
			canvas = null;
			frame = null;
			if (f != null) {
				f.dispose();
			}
			return true;
		} catch (LWJGLException | RuntimeException | LinkageError e) {
			LOG.warn("Flint: Instant Swap: couldn't take the window out of hiding", e);
			try {
				Display.setParent(null);
				canvas = null;
			} catch (LWJGLException | RuntimeException | LinkageError again) {
				LOG.warn("Flint: Instant Swap: the window stays hidden", again);
			}
			return false;
		}
	}

	/** While hidden: the canvas and the display take {@code width} x {@code height} (frames are drawn at the final size). */
	public static void resizeHidden(int width, int height) {
		Canvas c = canvas;
		if (c == null || width <= 0 || height <= 0) {
			return;
		}
		try {
			c.setBounds(0, 0, width, height);
			Display.setDisplayMode(new DisplayMode(width, height));
		} catch (LWJGLException | RuntimeException | LinkageError e) {
			LOG.warn("Flint: Instant Swap: couldn't size the hidden window", e);
		}
	}

	/** DWMWA_CLOAK on the window (Windows): drawn into, never on screen while cloaked. */
	public static void cloak(boolean on) {
		Pointer h = hwnd();
		if (h == null) {
			return;
		}
		try {
			com.sun.jna.Memory value = new com.sun.jna.Memory(4);
			value.setInt(0, on ? 1 : 0);
			int result = Function.getFunction("dwmapi", "DwmSetWindowAttribute").invokeInt(new Object[] {h, 13, value, 4});
			LOG.info("Flint: Instant Swap: window {} ({})", on ? "cloaked" : "uncloaked", result == 0 ? "ok" : "0x" + Integer.toHexString(result));
		} catch (RuntimeException | LinkageError e) {
			LOG.debug("Flint: Instant Swap: no cloak", e);
		}
	}

	/** The display's native window (Windows: its HWND), or null. */
	public static @Nullable Pointer hwnd() {
		if (!WINDOWS || !Display.isCreated()) {
			return null;
		}
		try {
			Field impl = Display.class.getDeclaredField("display_impl");
			impl.setAccessible(true);
			Object display = impl.get(null);
			Method get = display.getClass().getDeclaredMethod("getHwnd");
			get.setAccessible(true);
			long handle = (Long) get.invoke(display);
			return handle == 0 ? null : new Pointer(handle);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	/**
	 * Gives the window a client area of exactly {@code width} x {@code height} (Windows): a window LWJGL makes again can come
	 * out a few pixels off the size asked for (its frame worked out after the style changes), and the new game must match the
	 * old one's pixels. Its place is kept.
	 */
	public static void fitClient(int width, int height) {
		Pointer h = hwnd();
		if (h == null || width <= 0 || height <= 0) {
			return;
		}
		try {
			com.sun.jna.Memory client = new com.sun.jna.Memory(16);
			com.sun.jna.Memory outer = new com.sun.jna.Memory(16);
			Function.getFunction("user32", "GetClientRect").invokeInt(new Object[] {h, client});
			Function.getFunction("user32", "GetWindowRect").invokeInt(new Object[] {h, outer});
			int cw = client.getInt(8) - client.getInt(0);
			int ch = client.getInt(12) - client.getInt(4);
			if (cw == width && ch == height) {
				return;
			}
			int ow = outer.getInt(8) - outer.getInt(0);
			int oh = outer.getInt(12) - outer.getInt(4);
			// SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE
			Function.getFunction("user32", "SetWindowPos").invokeInt(new Object[] {h, Pointer.NULL, 0, 0, width + ow - cw, height + oh - ch, 0x2 | 0x4 | 0x10});
			LOG.info("Flint: Instant Swap: window client {}x{} -> {}x{}", cw, ch, width, height);
		} catch (RuntimeException | LinkageError e) {
			LOG.debug("Flint: Instant Swap: couldn't size the window", e);
		}
	}

	/** Whether the window is maximized (Windows), else false. */
	public static boolean maximized() {
		Pointer h = hwnd();
		if (h == null) {
			return false;
		}
		try {
			return Function.getFunction("user32", "IsZoomed").invokeInt(new Object[] {h}) != 0;
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** Hides the window (Windows). */
	public static void hideWindow() {
		Pointer h = hwnd();
		if (h != null) {
			try {
				Function.getFunction("user32", "ShowWindow").invokeInt(new Object[] {h, 0});
			} catch (RuntimeException | LinkageError e) {
				LOG.debug("Flint: Instant Swap: couldn't hide the window", e);
			}
		}
	}

	/** Maximizes the window (Windows). */
	public static void maximize() {
		Pointer h = hwnd();
		if (h != null) {
			try {
				Function.getFunction("user32", "ShowWindow").invokeInt(new Object[] {h, 3});
			} catch (RuntimeException | LinkageError e) {
				LOG.debug("Flint: Instant Swap: couldn't maximize", e);
			}
		}
	}

	/** Brings the window to the front with the keyboard (Windows; the old game allowed it). */
	public static void front() {
		Pointer h = hwnd();
		if (h != null) {
			try {
				Function.getFunction("user32", "SetForegroundWindow").invokeInt(new Object[] {h});
			} catch (RuntimeException | LinkageError e) {
				LOG.debug("Flint: Instant Swap: couldn't come to the front", e);
			}
		}
	}
}
