/*
 * Adapted from Natural Motion Blur 1.4.5 by Pasi, with IMS212, Romain H and What42Pizza (LGPL-3.0;
 * META-INF/licenses/LGPL-3.0-NaturalMotionBlur.txt): its blur strength scaling (BlurStrengthCalculator), the split of the
 * velocity blur around players and mobs (MixinLevelRenderer, velocity_pre / velocity_post / velocity_f5), its frame-blending
 * exposure weights and hybrid filler (FrameBlendingManager) and its accumulation factor.
 */
package com.flintclient.client.control.fx;

import java.util.Arrays;

/**
 * Natural Motion Blur's rules, free of Minecraft classes, so every Minecraft line (1.8.9 included) blurs the same way and the
 * rules are unit tested: which passes a frame draws, how strong the velocity blur is at this frame rate and the monitor's, how
 * many samples it takes, which kept frames a frame blend uses and with what weights, how much of the trail accumulation keeps,
 * and when the last frame's camera can't be blurred from (a pause, a teleport, a resize).
 *
 * <h2>How Natural Motion Blur looks smooth at any frame rate</h2>
 * <ul>
 * <li><b>Velocity based</b> (its recommended algorithm): every pixel's motion since the last frame is found from the depth
 * buffer and both frames' camera matrices (no history to lag behind, so no added input delay), then the image is sampled along
 * it. Sampling is done in linear light (squared colours), with a per-pixel jitter that hides the gaps between samples.</li>
 * <li><b>Refresh rate scaling</b>: the blur spans the motion of one monitor refresh, not one frame: at 600 FPS on 60 Hz each
 * frame's motion is a tenth as long, so it is blurred ten times as far. The blur then looks the same at 60 or 1,000 FPS, and the
 * frames a 60 Hz monitor never shows don't strobe.</li>
 * <li><b>Players and mobs stay sharp</b>: the blur is split around them. Before players and mobs are drawn, the terrain is
 * blurred by the part of its motion the camera's turn doesn't explain (moving through the world); after the world, everything
 * is blurred by the camera's turn only. A player you chase keeps still on screen and stays sharp, while the world rushes by. In
 * third person or riding, everything is blurred before players, mobs and your vehicle are drawn, and nothing after.</li>
 * <li><b>The hand and the HUD</b> are drawn after the blur and never blurred.</li>
 * </ul>
 */
public final class NmbTiming {
	/** The algorithms, in the order of the Blur algorithm setting ({@code VisualMods.MotionBlur.ALGORITHM}). */
	public static final int VELOCITY = 0;
	public static final int FRAME_BLENDING = 1;
	public static final int HYBRID = 2;
	public static final int ACCUMULATION_MAX = 3;
	public static final int ACCUMULATION_MIX = 4;

	/** The quality levels, in the order of the Quality setting. */
	public static final int PERFORMANCE = 0;
	public static final int BALANCED = 1;
	public static final int HIGH = 2;

	/** No velocity pass. */
	public static final int NONE = -1;
	/** The velocity shader's modes: the whole motion (Natural Motion Blur's {@code velocity_f5}). */
	public static final int MODE_FULL = 0;
	/** The camera's turn only (its {@code velocity_post}). */
	public static final int MODE_CAMERA = 1;
	/** What the camera's turn doesn't explain: moving through the world (its {@code velocity_pre}). */
	public static final int MODE_MOVEMENT = 2;

	/** The most kept frames one blend reads (Natural Motion Blur's OpenGL limit). */
	public static final int MAX_HISTORY = 12;
	/** A pixel's motion is clamped to this share of the screen per frame, as Natural Motion Blur does. */
	public static final float MAX_VELOCITY = 0.4F;
	/** The fewest samples a moving pixel takes (Natural Motion Blur's). */
	public static final int MIN_SAMPLES = 4;
	/** The Weighted profile takes this many times the samples (Natural Motion Blur's). */
	public static final float WEIGHTED_SAMPLES = 1.35F;
	/** The most samples any pixel takes at High, Weighted: the shaders' fixed loop bound. */
	public static final int LOOP_BOUND = 173;
	/**
	 * A frame that comes later than this after the last one restarts the blur (a stall, the world not drawn for a while, Dynamic
	 * FPS idling): its motion isn't a motion anyone saw, and a trail would show an image from before the pause.
	 */
	public static final float MAX_GAP_SECONDS = 0.25F;
	/** The camera moving farther than this between two frames (blocks) is a teleport, a respawn or a new camera, not motion. */
	public static final double MAX_JUMP = 16;
	/** The most of the trail one frame keeps: every frame still moves visibly toward the new image. */
	static final double MAX_KEPT = 0.985;

	private static int[] tmpSlots = new int[MAX_HISTORY * 2];
	private static float[] tmpWeights = new float[MAX_HISTORY * 2];

	private NmbTiming() {
	}

	// ---------- which passes ----------

	/** Whether {@code algorithm} draws the velocity blur (Velocity based and the hybrid's filler). */
	public static boolean usesVelocity(int algorithm) {
		return algorithm == VELOCITY || algorithm == HYBRID;
	}

	/** Whether {@code algorithm} keeps recent frames (Frame blending and the hybrid). */
	public static boolean usesFrameBlending(int algorithm) {
		return algorithm == FRAME_BLENDING || algorithm == HYBRID;
	}

	/** Whether {@code algorithm} is an accumulation trail. */
	public static boolean usesAccumulation(int algorithm) {
		return algorithm == ACCUMULATION_MAX || algorithm == ACCUMULATION_MIX;
	}

	/**
	 * The velocity mode drawn just before players, mobs and block entities ({@link #NONE} for none). {@code movement}: Blur when
	 * moving; {@code detached}: third person, or riding; {@code translating}: the camera moved since the last frame (not only
	 * turned). In first person, the terrain gets what the camera's turn doesn't explain (nothing with Blur when moving off, and
	 * nothing when the camera only turns: a turn moves every depth alike, so that part is exactly zero); detached, everything's
	 * motion (the turn only with it off), and nothing comes after.
	 */
	public static int earlyMode(boolean movement, boolean detached, boolean translating) {
		if (detached) {
			return movement ? MODE_FULL : MODE_CAMERA;
		}
		return movement && translating ? MODE_MOVEMENT : NONE;
	}

	/**
	 * The velocity mode drawn after the world and before the hand ({@link #NONE} for none). {@code earlyDrawn}: whether this
	 * frame's early pass ran (where the hook isn't reached, another mod drawing the terrain or a shader pack, the whole blur is
	 * drawn here instead: the camera's turn, and the movement too with Blur when moving on).
	 */
	public static int lateMode(boolean movement, boolean detached, boolean earlyDrawn, boolean translating) {
		if (earlyDrawn) {
			return detached ? NONE : MODE_CAMERA;
		}
		return movement && (detached || translating) ? MODE_FULL : MODE_CAMERA;
	}

	/**
	 * How far, in pixels, the camera's turn and zoom alone moved the image since the last frame: the most any corner or the
	 * centre of a {@code width} × {@code height} screen moved, reprojected through both frames' cameras (column-major 4 × 4
	 * matrices: this frame's inverse projection and inverse view, the last frame's view and projection; {@code depthNdc}: a
	 * clip-space depth between the near and far planes, where a turn moves every depth alike). A camera that only drifts (a
	 * field of view settling, a hair of mouse movement) moves the image under half a pixel and needs no pass at all.
	 */
	public static float turnPixels(float[] invProjection, float[] invView, float[] prevView, float[] prevProjection, float depthNdc,
		int width, int height) {
		float most = 0;
		for (int i = 0; i < 5; i++) {
			float x = i == 4 ? 0 : (i & 1) == 0 ? -1 : 1;
			float y = i == 4 ? 0 : (i & 2) == 0 ? -1 : 1;
			// The point in this frame's view space, then in the world (relative to the camera).
			float vx = dot(invProjection, 0, x, y, depthNdc, 1);
			float vy = dot(invProjection, 1, x, y, depthNdc, 1);
			float vz = dot(invProjection, 2, x, y, depthNdc, 1);
			float vw = dot(invProjection, 3, x, y, depthNdc, 1);
			if (!(Math.abs(vw) > 1e-12F)) {
				return Float.POSITIVE_INFINITY;
			}
			vx /= vw;
			vy /= vw;
			vz /= vw;
			float wx = dot(invView, 0, vx, vy, vz, 1);
			float wy = dot(invView, 1, vx, vy, vz, 1);
			float wz = dot(invView, 2, vx, vy, vz, 1);
			// Where the last frame's camera saw it.
			float px = dot(prevView, 0, wx, wy, wz, 1);
			float py = dot(prevView, 1, wx, wy, wz, 1);
			float pz = dot(prevView, 2, wx, wy, wz, 1);
			float pw = dot(prevView, 3, wx, wy, wz, 1);
			float cx = dot(prevProjection, 0, px, py, pz, pw);
			float cy = dot(prevProjection, 1, px, py, pz, pw);
			float cw = dot(prevProjection, 3, px, py, pz, pw);
			if (!(Math.abs(cw) > 1e-12F)) {
				return Float.POSITIVE_INFINITY;
			}
			float dx = (x - cx / cw) * 0.5F * width;
			float dy = (y - cy / cw) * 0.5F * height;
			float d = (float) Math.sqrt(dx * dx + dy * dy);
			if (Float.isNaN(d)) {
				return Float.POSITIVE_INFINITY;
			}
			most = Math.max(most, d);
		}
		return most;
	}

	/** Row {@code row} of column-major {@code m} times (x, y, z, w). */
	private static float dot(float[] m, int row, float x, float y, float z, float w) {
		return m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row] * w;
	}

	// ---------- velocity strength and samples ----------

	/**
	 * The velocity blur's strength: scaled up by how many frames are drawn per monitor refresh (if scaling), so each frame is
	 * blurred along the motion of a whole refresh period whatever the frame rate. {@code fps} is this frame's own rate (1 / its
	 * time), as Natural Motion Blur reads it: a frame's motion grows with its time, so the product stays steady.
	 */
	public static float velocityStrength(float strength, float fps, int refreshRate, boolean scaling) {
		return scaling ? strength * refreshRatio(fps, refreshRate) : strength;
	}

	static float refreshRatio(float fps, int refreshRate) {
		return Math.max(1, refreshRate > 0 && fps > 0 ? fps / refreshRate : 1);
	}

	/**
	 * Samples per screen of blur length. Balanced is Natural Motion Blur's own density at strength 1 (its 100 samples per
	 * screen of per-frame motion, scaled with the refresh ratio as the blur is). The count follows the blur's length on screen,
	 * so a short blur costs little and a still camera nothing.
	 */
	public static float sampleDensity(int quality) {
		return switch (quality) {
			case PERFORMANCE -> 50;
			case HIGH -> 200;
			default -> 100;
		};
	}

	/**
	 * The most samples one pixel takes (before the Weighted profile's 1.35 times). Natural Motion Blur's count grows with the
	 * frame rate (1,000 at 600 FPS on 60 Hz); a whole screen of that, while zooming or turning fast, stalls the GPU long enough
	 * for a driver to end the game, so the count stops here: 64 samples already cover a blur across half the screen at
	 * Natural Motion Blur's density.
	 */
	public static int maxSamples(int quality) {
		return switch (quality) {
			case PERFORMANCE -> 32;
			case HIGH -> 128;
			default -> 64;
		};
	}

	/**
	 * The samples a pixel whose blur is {@code length} screens long takes (what the shaders compute, for tests and the 1.8.9
	 * path): at least {@link #MIN_SAMPLES}, at most {@link #maxSamples}, 1.35 times as many in the Weighted profile.
	 */
	public static int samples(float length, int quality, boolean weighted) {
		int max = maxSamples(quality);
		int base = Math.max(MIN_SAMPLES, Math.min(max, (int) Math.ceil(length * sampleDensity(quality))));
		if (!weighted) {
			return base;
		}
		return Math.max(6, Math.min((int) Math.ceil(max * WEIGHTED_SAMPLES), (int) Math.ceil(base * WEIGHTED_SAMPLES)));
	}

	/** Hybrid blending's velocity "filler": as strong as the span one displayed frame covers, at least the blend's gaps. */
	public static float hybridFiller(float strength, float fps, float smoothedFps, int refreshRate, int filled) {
		float base = Math.max(0, strength);
		if (refreshRate <= 0 || fps <= 0) {
			return base;
		}
		float reference = Math.min(fps, refreshRate);
		float filler = Math.min(1, base * (fps / reference));
		float blendFps = smoothedFps <= 0 ? fps : smoothedFps * 0.85F + fps * 0.15F;
		float blendReference = Math.min(blendFps, refreshRate);
		int sampleCount = Math.min(MAX_HISTORY * 2, (int) Math.ceil(base * (blendFps / blendReference) - 1.0E-6F));
		sampleCount = Math.min(sampleCount, Math.min(MAX_HISTORY * 2, filled + 1));
		if (sampleCount > MAX_HISTORY) {
			int maxGap = 1;
			int previous = 0;
			for (int out = 1; out < MAX_HISTORY; out++) {
				int source = Math.round((float) out * (sampleCount - 1) / (MAX_HISTORY - 1));
				maxGap = Math.max(maxGap, source - previous);
				previous = source;
			}
			filler = Math.max(filler, maxGap);
		}
		return filler;
	}

	/** The frame rate Natural Motion Blur's frame blending reads: an average that follows each frame by 15%. */
	public static float smoothFps(float smoothed, float fps) {
		if (!(fps > 0)) {
			return smoothed;
		}
		return smoothed <= 0 ? fps : smoothed * 0.85F + fps * 0.15F;
	}

	// ---------- frame-to-frame continuity ----------

	/**
	 * Whether this frame can be blurred from the last one: it came within {@link #MAX_GAP_SECONDS} and the camera moved less
	 * than {@link #MAX_JUMP} blocks. Otherwise the blur starts over (velocity: this frame isn't blurred; trails and kept frames:
	 * they restart from this frame), instead of smearing a teleport or a pause across the screen.
	 */
	public static boolean continues(float frameSeconds, double dx, double dy, double dz) {
		return frameSeconds > 0 && frameSeconds <= MAX_GAP_SECONDS && dx * dx + dy * dy + dz * dz <= MAX_JUMP * MAX_JUMP;
	}

	// ---------- accumulation ----------

	/**
	 * The share of the trail an accumulation keeps under a frame that took {@code seconds} (its own time, not an average):
	 * Natural Motion Blur's factor 1 − ½^(strength × 7 / 3) (max) or × 5 / 3 (mix) per monitor refresh, to the power of how many
	 * refresh periods this frame lasted. Exactly its look when the game runs at the refresh rate, and the same trail length above
	 * it (Natural Motion Blur's own trail shortens as the frame rate rises); a slow frame (a hitch) shows mostly its own image
	 * instead of holding the old one and dragging the view behind.
	 */
	public static float kept(int mode, float strength, float seconds, int refreshRate) {
		double perPeriod = accumulation(strength, mode == ACCUMULATION_MAX);
		if (!(perPeriod > 0)) {
			return 0;
		}
		double periodsPerSecond = refreshRate > 0 ? refreshRate : 60;
		double periods = seconds > 0 ? Math.min(seconds, MAX_GAP_SECONDS) * periodsPerSecond : 1;
		return (float) Math.min(MAX_KEPT, Math.pow(perPeriod, periods));
	}

	/** Natural Motion Blur's accumulation factor at {@code strength}: 1 − ½^(strength × 7 / 3) for max, × 5 / 3 for mix. */
	public static float accumulation(float strength, boolean max) {
		return (float) (1.0 - Math.pow(0.5, Math.max(0, strength) * (max ? 7 : 5) / 3.0));
	}

	// ---------- frame blending ----------

	/**
	 * The kept frames inside the exposure ({@code strength} refresh periods ending at {@code now}, or frames when the game runs
	 * slower than the monitor): each frame's slot in {@code slots} and its weight in {@code weights} (its time inside), oldest
	 * first, at most {@code limit} (≤ {@link #MAX_HISTORY}); returns how many. {@code times}: when each kept frame ended
	 * (seconds), {@code order}: slots oldest first. Too many frames are spread evenly, each kept one taking the weight of those
	 * nearest it.
	 */
	public static int weights(double now, double[] times, int[] order, int filled, float fps, int refreshRate, float strength, int[] slots,
		float[] weights, int limit) {
		Arrays.fill(weights, 0);
		limit = Math.max(1, Math.min(limit, MAX_HISTORY));
		if (filled <= 0 || refreshRate <= 0) {
			return 0;
		}
		double reference = fps > 0 ? Math.min(fps, refreshRate) : refreshRate;
		double exposure = Math.max(0, strength) / reference;
		double start = now - exposure;
		double frameTime = fps > 0 ? 1.0 / fps : 1.0 / refreshRate;
		// Scratch kept between calls (render thread): nothing is allocated per frame.
		if (tmpSlots.length < filled) {
			tmpSlots = new int[filled];
			tmpWeights = new float[filled];
		}
		int count = 0;
		for (int i = 0; i < filled; i++) {
			double end = times[order[i]];
			if (end <= 0) {
				continue;
			}
			double begin = i > 0 ? times[order[i - 1]] : end - frameTime;
			if (begin >= end) {
				begin = end - frameTime;
			}
			double overlap = Math.min(end, now) - Math.max(begin, start);
			if (overlap > 1.0E-7) {
				tmpSlots[count] = order[i];
				tmpWeights[count] = (float) overlap;
				count++;
			}
		}
		if (count == 0) {
			return 0;
		}
		if (count <= limit) {
			System.arraycopy(tmpSlots, 0, slots, 0, count);
			System.arraycopy(tmpWeights, 0, weights, 0, count);
			return count;
		}
		if (limit == 1) {
			float total = 0;
			for (int i = 0; i < count; i++) {
				total += tmpWeights[i];
			}
			slots[0] = tmpSlots[count - 1];
			weights[0] = total;
			return 1;
		}
		for (int out = 0; out < limit; out++) {
			slots[out] = tmpSlots[Math.round((float) out * (count - 1) / (limit - 1))];
		}
		for (int source = 0; source < count; source++) {
			int out = Math.round((float) source * (limit - 1) / (count - 1));
			weights[Math.max(0, Math.min(limit - 1, out))] += tmpWeights[source];
		}
		return limit;
	}

	/** {@link #weights} with up to {@link #MAX_HISTORY} frames. */
	public static int weights(double now, double[] times, int[] order, int filled, float fps, int refreshRate, float strength, int[] slots,
		float[] weights) {
		return weights(now, times, order, filled, fps, refreshRate, strength, slots, weights, MAX_HISTORY);
	}
}
