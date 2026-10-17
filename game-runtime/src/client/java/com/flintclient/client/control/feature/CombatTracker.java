package com.flintclient.client.control.feature;

/**
 * What the client can honestly know about your fights, for Reach Display, Combo Counter, Hit Color and Kill Sounds:
 * <ul>
 * <li>an attack you made: the target and how far it was (eyes to the nearest point of its hitbox), measured as you
 * swing;</li>
 * <li>a hit confirmed by the server: it sends a damage event naming you as the cause;</li>
 * <li>damage you took and the direction the server gave for it;</li>
 * <li>a death the server announced for an entity you hit in the last {@link #KILL_WINDOW_MS}.</li>
 * </ul>
 * Pure Java; the version layer feeds it. Times are milliseconds from any monotonic clock. Render thread.
 */
public final class CombatTracker {
	/** A combo ends this long after your last landed hit. */
	public static final long COMBO_TIMEOUT_MS = 2000;
	/** A death counts as your kill if you hit the entity within this long before. */
	public static final long KILL_WINDOW_MS = 3000;
	/** A swing's distance is shown only if the server confirms the hit within this long. */
	static final long CONFIRM_MS = 1000;

	private int swingTarget = Integer.MIN_VALUE;
	private double swingDistance = Double.NaN;
	private long swingAt = Long.MIN_VALUE;

	private double reach = Double.NaN;
	private long reachAt = Long.MIN_VALUE;
	private int combo;
	private long lastHitAt = Long.MIN_VALUE;
	private int lastHitTarget = Integer.MIN_VALUE;
	private boolean lastHitPlayer;
	private long hitMarkerAt = Long.MIN_VALUE;
	private long killAt = Long.MIN_VALUE;
	private boolean killWasPlayer;
	private int kills;
	private long hurtAt = Long.MIN_VALUE;
	private float hurtYaw;

	/** You attacked entity {@code target} at {@code distance} blocks. */
	public void swing(int target, double distance, long now) {
		swingTarget = target;
		swingDistance = distance;
		swingAt = now;
	}

	/** The server says entity {@code target} was hurt by you. */
	public void landed(int target, boolean player, long now) {
		if (target == swingTarget && now - swingAt <= CONFIRM_MS) {
			reach = swingDistance;
			reachAt = now;
		}
		combo = now - lastHitAt <= COMBO_TIMEOUT_MS ? combo + 1 : 1;
		lastHitAt = now;
		lastHitTarget = target;
		lastHitPlayer = player;
		hitMarkerAt = now;
	}

	/** You were hurt; {@code yaw} is the direction relative to where you face (the server's hurt direction), or NaN. */
	public void hurt(float yaw, long now) {
		combo = 0;
		hurtAt = now;
		hurtYaw = yaw;
	}

	/** The server announced entity {@code target}'s death. Returns whether it counts as your kill. */
	public boolean died(int target, long now) {
		if (target == lastHitTarget && now - lastHitAt <= KILL_WINDOW_MS) {
			killAt = now;
			killWasPlayer = lastHitPlayer;
			kills++;
			lastHitTarget = Integer.MIN_VALUE;
			return true;
		}
		return false;
	}

	/** Forget everything (a new world). */
	public void reset() {
		swingTarget = Integer.MIN_VALUE;
		swingDistance = Double.NaN;
		swingAt = Long.MIN_VALUE;
		reach = Double.NaN;
		reachAt = Long.MIN_VALUE;
		combo = 0;
		lastHitAt = Long.MIN_VALUE;
		lastHitTarget = Integer.MIN_VALUE;
		hitMarkerAt = Long.MIN_VALUE;
		killAt = Long.MIN_VALUE;
		hurtAt = Long.MIN_VALUE;
	}

	/** The distance of your last confirmed hit if it was within {@code withinMs}, else NaN. */
	public double reach(long now, long withinMs) {
		return now - reachAt <= withinMs ? reach : Double.NaN;
	}

	/** Your current combo (0 when none). */
	public int combo(long now) {
		return now - lastHitAt <= COMBO_TIMEOUT_MS ? combo : 0;
	}

	public long hitMarkerAt() {
		return hitMarkerAt;
	}

	public long killAt() {
		return killAt;
	}

	public boolean killWasPlayer() {
		return killWasPlayer;
	}

	public int kills() {
		return kills;
	}

	public long hurtAt() {
		return hurtAt;
	}

	public float hurtYaw() {
		return hurtYaw;
	}
}
