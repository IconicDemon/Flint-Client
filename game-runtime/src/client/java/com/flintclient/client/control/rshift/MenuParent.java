package com.flintclient.client.control.rshift;

import org.jspecify.annotations.Nullable;

/**
 * A screen the Right Shift window opens from that Right Shift leaves altogether: the HUD page, which was itself opened over
 * the game (or a game screen). Closing the window with Right Shift shows {@link #returnTo()}; Escape and Close show the
 * page again.
 */
public interface MenuParent {
	/** The engine's screen the page was opened over (null: the game). */
	@Nullable Object returnTo();
}
