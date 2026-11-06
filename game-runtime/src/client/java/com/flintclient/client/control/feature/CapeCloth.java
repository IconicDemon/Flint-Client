package com.flintclient.client.control.feature;

/**
 * Wavy Capes' Realistic cloth: the realistic cloaks of the owner's Feather client (its ClothPiece and the cloak simulation that
 * drives it: the model, its constants and its two stiffnesses reused), stepped so it is smooth at any frame rate. A cape is two
 * chains of {@link #POINTS} points one pixel apart, hanging from the top of the back: the edges' (a little softer) and the
 * middle's, drawn as Feather draws them, edge, middle, middle, edge, so the cloth curves a little across as it moves. Points are
 * kept in the wearer's frame relative to the top: {@code away} (pixels behind the back), {@code down} and {@code side} (to the
 * wearer's right).
 *
 * <p>Each step every point drifts at a steady speed towards hanging (a few degrees behind straight down, more while crouching),
 * the top moves with the wearer (forward, up and down, sideways and with the body's turn; swimming, with the body's pitch), and
 * each link is set back to one pixel from the top down. There is no velocity to overshoot with: the cloth trails and settles,
 * it can't bounce, jitter or blow up, however hard it is moved. Feather steps it 60 times a second from the frame clock and
 * draws the newest step; here the steps are fixed (exactly 60 a second, at most a quarter second caught up at once), the top
 * stays at the origin (Feather's points wander off and lose float precision in a long session), and the drawing is
 * interpolated between the last two steps ({@link #alpha}), so it moves on every frame at 30, 144 or 360 FPS alike.
 * Pure Java, no allocation once made; one per cape in view.
 */
public final class CapeCloth {
	public static final int SEGMENTS = 16;
	public static final int POINTS = SEGMENTS + 1;
	/** The chains: the edges' and the middle's. */
	public static final int EDGE = 0;
	public static final int MIDDLE = 1;
	public static final int CHAINS = 2;
	/** One step: a sixtieth of a second, as Feather steps its cloaks. */
	public static final long STEP_NANOS = 16_666_667L;
	private static final float STEP_SECONDS = STEP_NANOS / 1e9F;
	/** At most this many steps are caught up at once (a quarter second); time beyond is dropped, not replayed. */
	private static final int MOST_STEPS = 15;
	/** Feather's stiffness for the edges' chain and the middle's (how fast each drifts back). */
	private static final float[] STIFFNESS = {0.85F, 1F};
	/** Feather's fall: 1.318 cape lengths (a block, 16 pixels) a second towards hanging. */
	private static final float FALL = 1.318F * 16;
	/** Feather's response to motion, pixels a step per block a tick: forward, up (and back as it rises), sideways. */
	private static final float FORWARD = 0.215F * 16;
	private static final float RISE = 0.119F * 16;
	private static final float RISE_BACK = 0.02F * 16;
	/** Sideways (strafing and turning) is Flint's: a gentle share of the forward response. */
	private static final float SIDEWAYS = FORWARD * 0.15F;
	/** How far behind the wearer's back the top is from the body's turning axis, in blocks (for the swing as the body turns). */
	private static final float TURN_RADIUS = 0.15F;
	/** Feather's response to the swimming body's pitch: pixels a step per degree, and the share it also moves forward. */
	private static final float PITCH = 0.0045F * 16;
	private static final float PITCH_FORWARD = 0.65F;
	/** Feather's hanging angles: degrees behind straight down, standing and crouching. */
	public static final float REST = 10;
	public static final float CROUCH = 31;
	/** Underwater the cloth drifts slower: it floats. */
	private static final float UNDERWATER = 0.4F;
	/** A wearer's motion beyond this (blocks a tick) is taken as this much. */
	private static final float FASTEST = 4;
	/** The least gravity the motion's response is divided by (Wavy Capes' lightest, 5 of 25). */
	private static final float LIGHTEST = 0.2F;

	/** What moves the cloth, read from the wearer each frame. Reused by the caller: no allocation per frame. */
	public static final class Motion {
		/** Blocks this tick the wearer moved forward (along the body's facing), up and to the right. */
		public float forward;
		public float up;
		public float right;
		/** Degrees the body turned this tick (to the right is positive), and its pitch (swimming) now. */
		public float turn;
		public float pitch;
		public boolean swimming;
		public boolean underwater;
		/** The cloth's hanging angle: degrees behind straight down along {@link #hangAway}/{@link #hangDown}. */
		public float angle = REST;
		/** Which way is down for the cloth, in the wearer's frame: behind and down, a unit vector (the body's own down: 0, 1). */
		public float hangAway;
		public float hangDown = 1;
		/**
		 * The settings: gravity (1 is Feather's default; more falls back quicker and, as Feather's gravity, divides how much motion
		 * moves it, so it lifts less), rise and fall, sideways swing.
		 */
		public float gravity = 1;
		public float height = 1;
		public float sideways = 1;

		/** Standing still, upright, with Feather's settings. */
		public Motion still() {
			forward = up = right = turn = pitch = 0;
			swimming = underwater = false;
			angle = REST;
			hangAway = 0;
			hangDown = 1;
			gravity = height = sideways = 1;
			return this;
		}
	}

	private final float[] away = new float[CHAINS * POINTS];
	private final float[] down = new float[CHAINS * POINTS];
	private final float[] side = new float[CHAINS * POINTS];
	private final float[] awayO = new float[CHAINS * POINTS];
	private final float[] downO = new float[CHAINS * POINTS];
	private final float[] sideO = new float[CHAINS * POINTS];
	/** The frame clock at the last advance (System.nanoTime), 0 before the first; time not yet stepped. */
	private long clock;
	private long carry;
	private float lastPitch = Float.NaN;

	/** A cape hanging at rest, as Feather starts one (every link at its standing angle). */
	public CapeCloth() {
		double r = Math.toRadians(REST);
		float a = (float) Math.sin(r);
		float d = (float) Math.cos(r);
		for (int c = 0; c < CHAINS; c++) {
			for (int i = 0; i < POINTS; i++) {
				away[c * POINTS + i] = a * i;
				down[c * POINTS + i] = d * i;
			}
		}
		copyToPrevious();
	}

	/** Steps the cloth {@code steps} times at once (a new cape settles into the wearer's motion before it is first drawn). */
	public void settle(Motion m, int steps) {
		for (int s = 0; s < steps; s++) {
			step(m);
		}
		copyToPrevious();
	}

	/**
	 * Brings the cloth up to {@code now} (System.nanoTime): as many fixed steps as the time since the last call holds, at most a
	 * quarter second's worth; what is left over is the drawing's {@link #alpha}.
	 */
	public void advance(long now, Motion m) {
		if (clock == 0) {
			clock = now;
			return;
		}
		long elapsed = Math.max(0, now - clock);
		clock = now;
		carry = Math.min(carry + elapsed, (MOST_STEPS + 1) * STEP_NANOS - 1);
		int steps = (int) (carry / STEP_NANOS);
		steps = Math.min(steps, MOST_STEPS);
		for (int s = 0; s < steps; s++) {
			step(m);
		}
		carry -= steps * STEP_NANOS;
	}

	/** Holds the cloth still at {@code now} (the game is paused): no steps, and none caught up afterwards. */
	public void hold(long now) {
		clock = now;
	}

	/** Where the drawing stands between the last two steps: 0 the one before, 1 the last. */
	public float alpha() {
		return Math.min(1F, carry / (float) STEP_NANOS);
	}

	/** Point {@code i} of chain {@code chain} at {@code t} between the last two steps: pixels behind the top. */
	public float away(int chain, int i, float t) {
		int k = chain * POINTS + i;
		return awayO[k] + (away[k] - awayO[k]) * t;
	}

	public float down(int chain, int i, float t) {
		int k = chain * POINTS + i;
		return downO[k] + (down[k] - downO[k]) * t;
	}

	public float side(int chain, int i, float t) {
		int k = chain * POINTS + i;
		return sideO[k] + (side[k] - sideO[k]) * t;
	}

	private void copyToPrevious() {
		System.arraycopy(away, 0, awayO, 0, away.length);
		System.arraycopy(down, 0, downO, 0, down.length);
		System.arraycopy(side, 0, sideO, 0, side.length);
	}

	/** One step (Feather's): the top moves with the wearer, every point drifts towards hanging, links go back to a pixel. */
	void step(Motion m) {
		copyToPrevious();
		float forward = sane(m.forward);
		float up = sane(m.up);
		float right = sane(m.right) - (float) Math.toRadians(finite(m.turn)) * TURN_RADIUS;
		right = Math.max(-FASTEST, Math.min(FASTEST, right));
		float pitch = finite(m.pitch);
		// Gravity: the cloth falls back faster, and (as Feather's gravity does) the motion moves it less.
		float gravity = Math.max(0, finite(m.gravity));
		float response = 1 / Math.max(gravity, LIGHTEST);
		forward *= response;
		up *= response;
		right *= response;
		// How the top moves this step (Feather moves it by each tick's motion every step).
		float topAway = 0;
		float topDown = 0;
		float topSide = 0;
		if (m.swimming) {
			// Swimming, only the body's pitch moves it (the cloth already streams along the body).
			if (!Float.isNaN(lastPitch)) {
				float turned = (pitch - lastPitch) * PITCH;
				topDown += turned;
				if (turned > 0) {
					topAway -= turned * PITCH_FORWARD;
				}
			}
		} else {
			if (forward > 0) {
				topAway -= forward * FORWARD;
			}
			float height = Math.max(0, finite(m.height));
			topDown -= up * RISE * height;
			topAway += up * RISE_BACK * height;
		}
		lastPitch = pitch;
		topSide += right * SIDEWAYS * Math.max(0, finite(m.sideways));
		// Where hanging is: the given down, turned back by the hanging angle.
		float hangAway = finite(m.hangAway);
		float hangDown = finite(m.hangDown);
		float hl = (float) Math.sqrt(hangAway * hangAway + hangDown * hangDown);
		if (hl < 1e-4F) {
			hangAway = 0;
			hangDown = 1;
		} else {
			hangAway /= hl;
			hangDown /= hl;
		}
		double r = Math.toRadians(finite(m.angle));
		float cos = (float) Math.cos(r);
		float sin = (float) Math.sin(r);
		float dirAway = hangAway * cos + hangDown * sin;
		float dirDown = hangDown * cos - hangAway * sin;
		float fall = FALL * STEP_SECONDS * gravity * (m.underwater ? UNDERWATER : 1);
		for (int c = 0; c < CHAINS; c++) {
			int base = c * POINTS;
			float drift = fall * STIFFNESS[c];
			// The top stays at the origin: the rest of the chain moves the other way, then drifts towards hanging.
			for (int i = 1; i < POINTS; i++) {
				int k = base + i;
				away[k] += dirAway * drift - topAway;
				down[k] += dirDown * drift - topDown;
				side[k] -= topSide;
			}
			// Each link back to one pixel from the top down, never in front of the back.
			for (int i = 1; i < POINTS; i++) {
				int k = base + i;
				float la = away[k] - away[k - 1];
				float ld = down[k] - down[k - 1];
				float ls = side[k] - side[k - 1];
				float len = (float) Math.sqrt(la * la + ld * ld + ls * ls);
				if (len < 1e-5F) {
					la = dirAway;
					ld = dirDown;
					ls = 0;
				} else {
					la /= len;
					ld /= len;
					ls /= len;
				}
				away[k] = Math.max(0, away[k - 1] + la);
				down[k] = down[k - 1] + ld;
				side[k] = side[k - 1] + ls;
			}
		}
	}

	/** A finite input, else none. */
	private static float finite(float value) {
		return Float.isFinite(value) ? value : 0;
	}

	/** A finite motion within the fastest a wearer moves. */
	private static float sane(float value) {
		return Math.max(-FASTEST, Math.min(FASTEST, finite(value)));
	}
}
