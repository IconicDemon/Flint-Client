package com.flintclient.client.control.feature;

/**
 * Wavy Capes' cloth, as Wavey Capes moves it (behaviour matched; Flint's own code): a cape as a chain of
 * {@link #POINTS} points one pixel apart, hanging from the top of the back, in the wearer's own frame: {@code away} (pixels
 * behind the back, at most level with the top), {@code down} (below the top) and {@code side} (to the wearer's right).
 * Each game tick the top is moved by the wearer's motion (forward and back, up and down, sideways) and the rest follows:
 * gravity pulls the points, links keep their length over a few relaxation passes, the cape stays behind the back and only
 * swings so far to a side, and doesn't bend sharply. Three kinds: {@link #FLAT} (no sideways motion, gentle bends),
 * {@link #THREE_D} (sideways, no point passes another) and {@link #DUNGEONS} (stiff, bending only at its middle).
 * Pure Java; one per player in view.
 */
public final class CapeSim {
	public static final int SEGMENTS = 16;
	public static final int POINTS = SEGMENTS;
	public static final int FLAT = 1;
	public static final int THREE_D = 2;
	public static final int DUNGEONS = 3;

	/** What the cloth answers to: gravity (pixels a tick at full), the gravity's direction in the wearer's frame. */
	public record Settings(float gravity, float gravityAway, float gravityDown, float gravitySide) {
	}

	private final int kind;
	private final int passes;
	private final float[] x = new float[POINTS];
	private final float[] y = new float[POINTS];
	private final float[] z = new float[POINTS];
	private final float[] px = new float[POINTS];
	private final float[] py = new float[POINTS];
	private final float[] pz = new float[POINTS];
	private float dirX;
	private float dirY;
	private float dirZ;
	private boolean crouching;

	/** A cape of the given kind ({@link #FLAT}, {@link #THREE_D} or {@link #DUNGEONS}), hanging still. */
	public CapeSim(int kind) {
		this.kind = kind;
		this.passes = kind == DUNGEONS ? 30 : 3;
		for (int i = 0; i < POINTS; i++) {
			// Internally x grows forward (towards the body), y up and z to the left, as the motion arrives.
			y[i] = -i;
			x[i] = kind == THREE_D ? -i : 0;
		}
		System.arraycopy(x, 0, px, 0, POINTS);
		System.arraycopy(y, 0, py, 0, POINTS);
		System.arraycopy(z, 0, pz, 0, POINTS);
	}

	public int kind() {
		return kind;
	}

	/** Point {@code i} between the last two ticks ({@code t}: 0 the one before, 1 the last): pixels behind the top. */
	public float away(int i, float t) {
		float a = (px[i] - px[0]) + ((x[i] - x[0]) - (px[i] - px[0])) * t;
		return -Math.min(0, a);
	}

	public float down(int i, float t) {
		return (py[0] - py[i]) + ((y[0] - y[i]) - (py[0] - py[i])) * t;
	}

	public float side(int i, float t) {
		return (pz[0] - pz[i]) + ((z[0] - z[i]) - (pz[0] - pz[i])) * t;
	}

	/** The tilt of link {@code i} (to the next point) from hanging straight down, towards behind, in degrees. */
	public float linkAngle(int i, float t) {
		int a = i >= POINTS - 1 ? POINTS - 2 : i;
		float ax = px[a] + (x[a] - px[a]) * t;
		float ay = py[a] + (y[a] - py[a]) * t;
		float bx = px[a + 1] + (x[a + 1] - px[a + 1]) * t;
		float by = py[a + 1] + (y[a + 1] - py[a + 1]) * t;
		return (float) (Math.toDegrees(Math.atan2(bx - ax, by - ay)) + 180.0);
	}

	/**
	 * One game tick: the wearer moved the top by {@code back} (pixels, backwards counted forward of the cloth), {@code up}
	 * and {@code left}; {@code crouching} kicks the cloth once as they crouch.
	 */
	public void step(float back, float up, float left, boolean crouchingNow, Settings s) {
		float dx = back + (crouchingNow && !crouching ? 3 : 0);
		float dy = up + (crouchingNow && !crouching ? 1 : 0);
		crouching = crouchingNow;
		px[0] = x[0];
		py[0] = y[0];
		pz[0] = z[0];
		x[0] += dx;
		y[0] += dy;
		if (kind != FLAT) {
			z[0] += left;
		}
		// Gravity pulls every point but the top.
		float fall = s.gravity() * 0.05F;
		for (int i = 1; i < POINTS; i++) {
			px[i] = x[i];
			py[i] = y[i];
			pz[i] = z[i];
			x[i] -= s.gravityAway() * fall;
			y[i] -= s.gravityDown() * fall;
			if (kind != FLAT) {
				z[i] -= s.gravitySide() * fall;
			}
		}
		keepBehind();
		switch (kind) {
			case FLAT -> {
				limitBendsFlat();
				relax();
			}
			case THREE_D -> {
				keepApart(32);
				relax();
				keepApart(32);
				for (int i = 1; i < POINTS - 2; i++) {
					limitBend(i, 20);
				}
			}
			default -> {
				relax();
				for (int i = 1; i < POINTS - 1; i++) {
					limitBend(i, i == POINTS / 2 ? 20 : 0);
				}
				keepApart(10);
			}
		}
		// Every link exactly one pixel, from the top down.
		for (int a = 0; a < POINTS - 1; a++) {
			direction(a, a + 1);
			x[a + 1] = x[a] - dirX;
			y[a + 1] = y[a] - dirY;
			z[a + 1] = z[a] - dirZ;
		}
	}

	/** The cloth never passes in front of the top (into the back), and swings less to a side the higher up it is. */
	private void keepBehind() {
		for (int i = 1; i < POINTS; i++) {
			if (x[i] > x[0]) {
				x[i] = x[0];
			}
			float share = (float) i / POINTS;
			float most = share * share * 5;
			float off = z[0] - z[i];
			if (off > most) {
				z[i] = z[0] - most;
			} else if (off < -most) {
				z[i] = z[0] + most;
			}
		}
	}

	/** Links pulled back to one pixel, from the bottom up, a few times over. */
	private void relax() {
		for (int pass = 0; pass < passes; pass++) {
			for (int a = POINTS - 2; a >= 0; a--) {
				direction(a, a + 1);
				pushApart(a, a + 1);
			}
		}
	}

	/** No two points closer than a pixel (the cloth doesn't fold through itself). */
	private void keepApart(int most) {
		int runs = 0;
		boolean moved;
		do {
			moved = false;
			for (int a = 0; a < POINTS; a++) {
				for (int b = a + 1; b < POINTS; b++) {
					float ex = x[a] - x[b];
					float ey = y[a] - y[b];
					float ez = z[a] - z[b];
					if (ex * ex + ey * ey + ez * ez < 0.99F) {
						moved = true;
						runs++;
						direction(a, b);
						pushApart(a, b);
					}
				}
			}
		} while (moved && runs < most);
	}

	/** The flat cloth's bends: each joint kept within five degrees of straight (seen from the side). */
	private void limitBendsFlat() {
		for (int i = POINTS - 2; i >= 1; i--) {
			double angle = Math.toDegrees(Math.atan2(y[i + 1] - y[i], x[i + 1] - x[i]) - Math.atan2(y[i - 1] - y[i], x[i - 1] - x[i]));
			if (angle > 360) {
				angle -= 360;
			} else if (angle < -360) {
				angle += 360;
			}
			double abs = Math.abs(angle);
			if (abs < 175) {
				turnNext(i, angle, 176);
			} else if (abs > 185) {
				turnNext(i, angle, 184);
			}
		}
	}

	private void turnNext(int i, double angle, double target) {
		double theta = Math.toRadians(target) * (angle < 0 ? -1 : 1);
		float ox = x[i - 1] - x[i];
		float oy = y[i - 1] - y[i];
		x[i + 1] = (float) (ox * Math.cos(theta) - oy * Math.sin(theta) + x[i]);
		y[i + 1] = (float) (ox * Math.sin(theta) + oy * Math.cos(theta) + y[i]);
	}

	/** Joint {@code i} bent at most {@code most} degrees (seen from the side): the next point swung back within it. */
	private void limitBend(int i, float most) {
		float abx = x[i - 1] - x[i];
		float aby = y[i - 1] - y[i];
		float cbx = x[i - 1] - x[i + 1];
		float cby = y[i - 1] - y[i + 1];
		double angle = Math.toDegrees(Math.atan2(abx * cby - aby * cbx, abx * cbx + aby * cby));
		if (angle < -most) {
			swingNext(i, -most * 2);
		} else if (angle > most) {
			swingNext(i, most * 2);
		}
	}

	private void swingNext(int i, float degrees) {
		float ox = x[i] - x[i - 1];
		float oy = y[i] - y[i - 1];
		float oz = z[i] - z[i - 1];
		double r = Math.toRadians(degrees);
		float c = (float) Math.cos(r);
		float s = (float) Math.sin(r);
		x[i + 1] = c * ox - s * oy + x[i];
		y[i + 1] = s * ox + c * oy + y[i];
		z[i + 1] = oz + z[i];
	}

	private void pushApart(int a, int b) {
		float cx = (x[a] + x[b]) / 2;
		float cy = (y[a] + y[b]) / 2;
		float cz = (z[a] + z[b]) / 2;
		if (a != 0) {
			x[a] = cx + dirX / 2;
			y[a] = cy + dirY / 2;
			z[a] = cz + dirZ / 2;
		}
		x[b] = cx - dirX / 2;
		y[b] = cy - dirY / 2;
		z[b] = cz - dirZ / 2;
	}

	private void direction(int a, int b) {
		dirX = x[a] - x[b];
		dirY = y[a] - y[b];
		dirZ = z[a] - z[b];
		float len = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
		if (len < 1.0E-4F) {
			dirX = dirY = dirZ = 0;
		} else {
			dirX /= len;
			dirY /= len;
			dirZ /= len;
		}
	}
}
