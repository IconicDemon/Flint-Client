package com.flintclient.client.control.model;

/**
 * The facts that decide whether a module can show anything right now. Built by the platform layer from real
 * state (refresh it on a tick or a screen change, not per frame).
 *
 * @param inWorld a level is loaded
 * @param multiplayer connected to a server (not singleplayer)
 * @param latencyKnown the server has reported this player's latency
 * @param networkCoreRunning Flint Network Core is running this session
 */
public record ControlContext(boolean inWorld, boolean multiplayer, boolean latencyKnown, boolean networkCoreRunning) {
	/**
	 * The title screen. Network Core's state only matters on a server, so it is left false here; the Settings
	 * page reads the real state for its own row.
	 */
	public static final ControlContext TITLE_SCREEN = new ControlContext(false, false, false, false);
}
