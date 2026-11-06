package com.flintclient.legacy.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Mouse;

/**
 * Interaction for Flint's screens, drawn anew every frame: each frame the screen registers the rectangles that react (with a
 * name, what a click does, what a drag does, what the wheel does) as it draws them; input goes to the topmost one under the
 * pointer from the frame before. Hover, press and other animations ease on real frame time, kept by name between frames.
 * Every reacting rectangle has an accessible name, which is also how QA finds it. Render thread.
 */
public final class Ui {
	/** Something that reacts. */
	public static final class Hit {
		public String name;
		public int x;
		public int y;
		public int w;
		public int h;
		Runnable click;
		Drag drag;
		Wheel wheel;
		String tooltip;
		int clipX;
		int clipY;
		int clipW;
		int clipH;

		boolean contains(int px, int py) {
			return px >= x && px < x + w && py >= y && py < y + h && px >= clipX && px < clipX + clipW && py >= clipY && py < clipY + clipH;
		}
	}

	/** A drag: called with the pointer as it moves while the button is held (and once when it is pressed). */
	public interface Drag {
		void to(int px, int py, boolean first);
	}

	/** A scroll: wheel notches (positive is up). */
	public interface Wheel {
		void scrolled(int notches);
	}

	private static List<Hit> shown = new ArrayList<Hit>();
	private static List<Hit> building = new ArrayList<Hit>();
	private static final Map<String, float[]> ANIMS = new HashMap<String, float[]>();
	private static Hit hot;
	private static Hit pressed;
	private static String hoverName;
	private static long hoverSince;
	private static long lastFrame;
	private static float dt;
	private static int pointerX;
	private static int pointerY;
	/** QA's pointer (window pixels), or -1 for the real mouse. */
	private static int qaX = -1;
	private static int qaY = -1;
	private static String focus;
	private static final int[] CLIP = {0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE};
	private static final int[] CLIP_STACK = new int[64];
	private static int clipDepth;

	private Ui() {
	}

	// ---------- the frame ----------

	/** Starts a frame: reads the pointer, finds what is under it, and times the frame. */
	public static void frame() {
		long now = System.nanoTime();
		dt = lastFrame == 0 ? 0.016F : Math.min(0.1F, (now - lastFrame) / 1e9F);
		lastFrame = now;
		readPointer();
		// What was registered while the last frame was drawn is what input acts on now.
		List<Hit> swap = shown;
		shown = building;
		building = swap;
		building.clear();
		hot = topmost(pointerX, pointerY);
		String name = hot == null ? null : hot.name;
		if (name == null ? hoverName != null : !name.equals(hoverName)) {
			hoverName = name;
			hoverSince = System.currentTimeMillis();
		}
		CLIP[0] = 0;
		CLIP[1] = 0;
		CLIP[2] = Integer.MAX_VALUE;
		CLIP[3] = Integer.MAX_VALUE;
		clipDepth = 0;
		if (pressed != null && pressed.drag != null) {
			pressed.drag.to(pointerX, pointerY, false);
		}
	}

	private static void readPointer() {
		if (qaX >= 0) {
			pointerX = qaX;
			pointerY = qaY;
		} else {
			pointerX = Mouse.getX();
			pointerY = Minecraft.getInstance().height - 1 - Mouse.getY();
		}
	}

	/** Seconds since the last frame. */
	public static float dt() {
		return dt;
	}

	public static int pointerX() {
		return pointerX;
	}

	public static int pointerY() {
		return pointerY;
	}

	/** QA moves the pointer (window pixels). */
	public static void qaMove(int x, int y) {
		qaX = x;
		qaY = y;
	}

	// ---------- registering ----------

	/** Limits the hits registered next to a rectangle (a scrolled list's viewport). */
	public static void pushClip(int x, int y, int w, int h) {
		CLIP_STACK[clipDepth * 4] = CLIP[0];
		CLIP_STACK[clipDepth * 4 + 1] = CLIP[1];
		CLIP_STACK[clipDepth * 4 + 2] = CLIP[2];
		CLIP_STACK[clipDepth * 4 + 3] = CLIP[3];
		clipDepth++;
		int x0 = Math.max(CLIP[0], x);
		int y0 = Math.max(CLIP[1], y);
		long x1 = Math.min((long) CLIP[0] + CLIP[2], (long) x + w);
		long y1 = Math.min((long) CLIP[1] + CLIP[3], (long) y + h);
		CLIP[0] = x0;
		CLIP[1] = y0;
		CLIP[2] = (int) Math.max(0, x1 - x0);
		CLIP[3] = (int) Math.max(0, y1 - y0);
		Gfx.pushClip(x, y, w, h);
	}

	public static void popClip() {
		clipDepth--;
		CLIP[0] = CLIP_STACK[clipDepth * 4];
		CLIP[1] = CLIP_STACK[clipDepth * 4 + 1];
		CLIP[2] = CLIP_STACK[clipDepth * 4 + 2];
		CLIP[3] = CLIP_STACK[clipDepth * 4 + 3];
		Gfx.popClip();
	}

	/** Registers a rectangle that reacts; returns it (for a drag, a wheel or a tooltip). */
	public static Hit hit(String name, float x, float y, float w, float h, Runnable click) {
		Hit hit = new Hit();
		hit.name = name;
		hit.x = Math.round(x);
		hit.y = Math.round(y);
		hit.w = Math.round(x + w) - hit.x;
		hit.h = Math.round(y + h) - hit.y;
		hit.click = click;
		hit.drag = null;
		hit.wheel = null;
		hit.tooltip = null;
		hit.clipX = CLIP[0];
		hit.clipY = CLIP[1];
		hit.clipW = CLIP[2];
		hit.clipH = CLIP[3];
		building.add(hit);
		return hit;
	}

	public static Hit drag(Hit hit, Drag drag) {
		hit.drag = drag;
		return hit;
	}

	public static Hit wheel(Hit hit, Wheel wheel) {
		hit.wheel = wheel;
		return hit;
	}

	public static Hit tooltip(Hit hit, String text) {
		hit.tooltip = text;
		return hit;
	}

	/** Whether the pointer is over the rectangle named {@code name} (as of the last frame). */
	public static boolean hovered(String name) {
		return hot != null && name.equals(hot.name);
	}

	public static boolean pressed(String name) {
		return pressed != null && name.equals(pressed.name) && hot == pressed;
	}

	/** Whether anything is being dragged. */
	public static boolean dragging() {
		return pressed != null && pressed.drag != null;
	}

	/** The tooltip to show now: the hovered rectangle's, once the pointer has rested on it for a moment. */
	public static String tooltip() {
		if (hot == null || hot.tooltip == null || pressed != null || System.currentTimeMillis() - hoverSince < 450) {
			return null;
		}
		return hot.tooltip;
	}

	// ---------- animation ----------

	/** An eased value kept under {@code key}: moves toward {@code on}; the first frame starts there. */
	public static float ease(String key, boolean on) {
		float[] v = ANIMS.get(key);
		if (v == null) {
			v = new float[] {on ? 1 : 0};
			ANIMS.put(key, v);
			return v[0];
		}
		float target = on ? 1 : 0;
		float k = 1 - (float) Math.exp(-dt / 0.045F);
		v[0] += (target - v[0]) * k;
		if (Math.abs(v[0] - target) < 0.004F) {
			v[0] = target;
		}
		return v[0];
	}

	/** The hover amount of {@code name}, eased. */
	public static float hover(String name) {
		return ease("h:" + name, hovered(name));
	}

	/** The press amount of {@code name}, eased. */
	public static float press(String name) {
		return ease("p:" + name, pressed(name));
	}

	// ---------- focus (text fields, key capture) ----------

	public static String focus() {
		return focus;
	}

	public static void setFocus(String name) {
		focus = name;
	}

	// ---------- input ----------

	private static Hit topmost(int px, int py) {
		for (int i = shown.size() - 1; i >= 0; i--) {
			Hit h = shown.get(i);
			if (h.contains(px, py)) {
				return h;
			}
		}
		return null;
	}

	/** A button went down at the pointer; true when something took it. */
	public static boolean mouseDown(int button) {
		readPointer();
		Hit h = topmost(pointerX, pointerY);
		if (button != 0) {
			return h != null;
		}
		pressed = h;
		if (h != null && h.drag != null) {
			h.drag.to(pointerX, pointerY, true);
		}
		return h != null;
	}

	public static void mouseUp(int button) {
		readPointer();
		Hit was = pressed;
		pressed = null;
		if (button != 0 || was == null) {
			return;
		}
		Hit now = topmost(pointerX, pointerY);
		if (was.click != null && now != null && now.name.equals(was.name)) {
			was.click.run();
		}
	}

	/** Wheel notches at the pointer: the topmost scroller under it takes them. */
	public static void wheel(int notches) {
		readPointer();
		for (int i = shown.size() - 1; i >= 0; i--) {
			Hit h = shown.get(i);
			if (h.wheel != null && h.contains(pointerX, pointerY)) {
				h.wheel.scrolled(notches);
				return;
			}
		}
	}

	/** Forgets presses and focus (a screen opened or closed). */
	public static void reset() {
		pressed = null;
		focus = null;
		shown.clear();
		building.clear();
		hot = null;
	}

	/** QA: the rectangle named {@code name} in the last frame, or null. */
	public static int[] bounds(String name) {
		for (int i = shown.size() - 1; i >= 0; i--) {
			Hit h = shown.get(i);
			if (name.equalsIgnoreCase(h.name)) {
				return new int[] {h.x, h.y, h.w, h.h};
			}
		}
		return null;
	}
}
