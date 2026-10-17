package com.flintclient.client.control.feature;

import java.lang.management.ManagementFactory;

/**
 * The state Flint's feature modules share across Minecraft versions: the combat tracker, the session clocks and the
 * movement toggles. Version-neutral; the version layer feeds it from its hooks and reads it back. Render thread.
 */
public final class Features {
	public static final CombatTracker COMBAT = new CombatTracker();
	public static final MovementToggles MOVEMENT = new MovementToggles();
	public static final ZoomState ZOOM = new ZoomState();
	public static final FreelookState FREELOOK = new FreelookState();
	public static final SpeedTracker SPEED = new SpeedTracker();
	private static @org.jspecify.annotations.Nullable WaypointStore waypoints;
	private static @org.jspecify.annotations.Nullable PackFolders packFolders;
	/** When the game process started (wall clock, ms). */
	private static final long LAUNCHED_AT = launchTime();
	/** When the current world was joined (monotonic ms), or -1 outside a world. */
	private static long worldJoinedAt = -1;

	private Features() {
	}

	private static long launchTime() {
		try {
			return ManagementFactory.getRuntimeMXBean().getStartTime();
		} catch (RuntimeException | Error e) {
			return System.currentTimeMillis();
		}
	}

	/** Reads where Flint keeps per-world data (once, at start-up). */
	public static void init(java.nio.file.Path configDir) {
		waypoints = new WaypointStore(configDir.resolve("flintclient").resolve("waypoints"));
		packFolders = new PackFolders(configDir.resolve("flintclient").resolve("pack-folders.json"));
	}

	/** Pack Organizer's folders (kept in memory only before start-up finished). */
	public static PackFolders packFolders() {
		PackFolders folders = packFolders;
		if (folders == null) {
			folders = new PackFolders(null);
			packFolders = folders;
		}
		return folders;
	}

	/** This world's waypoints (an empty store before start-up finished). */
	public static WaypointStore waypoints() {
		WaypointStore store = waypoints;
		if (store == null) {
			store = new WaypointStore(java.nio.file.Path.of("config", "flintclient", "waypoints"));
			waypoints = store;
		}
		return store;
	}

	/** Monotonic milliseconds, the clock every tracker here uses. */
	public static long nowMs() {
		return System.nanoTime() / 1_000_000L;
	}

	/** Milliseconds since the game started. */
	public static long sinceLaunchMs() {
		return Math.max(0, System.currentTimeMillis() - LAUNCHED_AT);
	}

	/** Milliseconds since this world was joined, or -1 outside a world. */
	public static long inWorldMs() {
		return worldJoinedAt < 0 ? -1 : nowMs() - worldJoinedAt;
	}

	/** A world was joined ({@code true}) or left. */
	public static void worldChanged(boolean inWorld) {
		worldJoinedAt = inWorld ? nowMs() : -1;
		COMBAT.reset();
		MOVEMENT.reset();
		SPEED.reset();
	}
}
