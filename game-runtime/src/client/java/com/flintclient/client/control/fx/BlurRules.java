/*
 * Smooth is adapted from AFKZ Motion Blur 1.0 by Neerajisafk (MIT; META-INF/licenses/MIT-AFKZMotionBlur.txt): its temporal
 * blend of the last blended frame into the new one, its default look and its history resets. Natural is Natural Motion Blur
 * 1.4.5's velocity blur (LGPL-3.0; see NmbTiming).
 */
package com.flintclient.client.control.fx;

/**
 * Motion Blur's two modes and the rules every Minecraft line shares, free of Minecraft classes (unit tested).
 *
 * <ul>
 * <li><b>Smooth</b> (the default): the trail Lunar Client, AFKZ Motion Blur and most PvP clients draw. Each frame keeps a share
 * of the last blended frame and adds the new one on top, in linear light. AFKZ keeps a fixed share per frame (its default
 * keeps 0.675), so its trail shortens as the frame rate rises; here the share follows the time the frame took
 * ({@link #smoothKept}): the trail fades with a time constant ({@link #trailSeconds}), at the default strength AFKZ's default as
 * it looks at 144 FPS, so it looks the same at 60 and 240 FPS (at 240 FPS it is simply made of more frames, smoother still).</li>
 * <li><b>Natural</b>: Natural Motion Blur's velocity blur ({@link NmbTiming}), each pixel blurred along its own motion. It needs
 * Minecraft's depth buffer as the world drew it; a shader pack draws the world through its own buffers, so with one on, Natural
 * blurs as Smooth instead ({@link #effectiveMode}) rather than smearing blocks the depth buffer no longer describes.</li>
 * </ul>
 *
 * <p>The trail starts over ({@link Scene}) whenever the last frame isn't a frame of the same view: a new world or dimension, a
 * respawn, a resize or fullscreen switch, a screen opening or closing, pausing, the window losing or regaining focus, F1, F5, a
 * shader pack turned on or off, a stall or a teleport ({@link NmbTiming#continues}), as AFKZ resets its history.
 */
public final class BlurRules {
	/** The modes, in the order of the Mode setting ({@code VisualMods.MotionBlur.MODE}). */
	public static final int SMOOTH = 0;
	public static final int NATURAL = 1;

	/** The default strength (percent): AFKZ Motion Blur's default look in Smooth, Natural Motion Blur's 1.00 in Natural. */
	public static final int DEFAULT_STRENGTH = 50;
	/** The most of the trail one frame keeps (at the strongest setting and a very high frame rate). */
	static final double MAX_KEPT = 0.995;
	/** Smooth's time constant at 100% (seconds): a long, heavy trail. */
	static final double MAX_TRAIL_SECONDS = 0.06;
	/** AFKZ Motion Blur's default (its strongest preset) keeps 0.675 of the trail per frame; at 144 FPS that fades this fast. */
	static final double AFKZ_DEFAULT_TRAIL_SECONDS = (1 / 144.0) / -Math.log(0.675);
	/** The strength curve's exponent: 50% is {@link #AFKZ_DEFAULT_TRAIL_SECONDS}, 100% {@link #MAX_TRAIL_SECONDS}. */
	private static final double CURVE = Math.log(MAX_TRAIL_SECONDS / AFKZ_DEFAULT_TRAIL_SECONDS) / Math.log(2);

	private BlurRules() {
	}

	/** {@code mode} as drawn: Natural blurs as Smooth while a shader pack draws the world. */
	public static int effectiveMode(int mode, boolean shaderPack) {
		return mode == NATURAL && !shaderPack ? NATURAL : SMOOTH;
	}

	/**
	 * Smooth's time constant at {@code percent} strength (seconds): the trail fades to a third of itself in this long. 0.06 × s^1.76,
	 * s the strength from 0 to 1: 50% is AFKZ Motion Blur's default as it looks at 144 FPS (17.7 ms), 100% 60 ms, 25% 5 ms.
	 */
	public static double trailSeconds(int percent) {
		double s = Math.max(0, Math.min(100, percent)) / 100.0;
		return MAX_TRAIL_SECONDS * Math.pow(s, CURVE);
	}

	/**
	 * The share of the trail Smooth keeps under a frame that took {@code seconds}: e^(−seconds / {@link #trailSeconds}), so the
	 * trail fades by time, not by frames. A slow frame (a hitch, at most a quarter second counts) shows mostly itself; an unknown
	 * frame time counts as a sixtieth.
	 */
	public static float smoothKept(int percent, float seconds) {
		double tau = trailSeconds(percent);
		if (!(tau > 0)) {
			return 0;
		}
		double t = seconds > 0 ? Math.min(seconds, NmbTiming.MAX_GAP_SECONDS) : 1 / 60.0;
		return (float) Math.min(MAX_KEPT, Math.exp(-t / tau));
	}

	/** Natural's velocity strength at {@code percent}: Natural Motion Blur's 0–2, 50% its default 1.00. */
	public static float naturalStrength(int percent) {
		return Math.max(0, Math.min(100, percent)) / 50F;
	}

	/** The scene flags {@link Scene#same} compares: a screen open, paused, the window focused, the HUD hidden, a shader pack on. */
	public static int flags(boolean screen, boolean paused, boolean focused, boolean hudHidden, boolean shaderPack) {
		return (screen ? 1 : 0) | (paused ? 2 : 0) | (focused ? 4 : 0) | (hudHidden ? 8 : 0) | (shaderPack ? 16 : 0);
	}

	/**
	 * What the last frame showed: the world and player (by identity), the image size, the camera (first or third person), the
	 * mode and the {@link #flags}. A frame that differs in any of them starts the blur over. Render thread only; allocation free.
	 */
	public static final class Scene {
		private boolean ready;
		private int level;
		private int player;
		private int width;
		private int height;
		private int camera;
		private int mode;
		private int flags;

		/** Whether this frame shows the same scene as the last one; it becomes the last one either way. */
		public boolean same(int level, int player, int width, int height, int camera, int mode, int flags) {
			boolean same = ready && level == this.level && player == this.player && width == this.width && height == this.height
				&& camera == this.camera && mode == this.mode && flags == this.flags;
			ready = true;
			this.level = level;
			this.player = player;
			this.width = width;
			this.height = height;
			this.camera = camera;
			this.mode = mode;
			this.flags = flags;
			return same;
		}

		/** The next frame starts over. */
		public void reset() {
			ready = false;
		}
	}
}
