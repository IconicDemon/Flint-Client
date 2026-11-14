package com.flintclient.client.control.rshift;

import com.flintclient.client.ui.kit.Node;
import org.jspecify.annotations.Nullable;

/**
 * Discover: the servers and communities Flint partners with, a window of its own (as Friends and Cosmetics are), opened from
 * the main menu's Discover button. The listings are the Flint app's partner feed (Library → Partnerships in the launcher),
 * through the game link: each with its picture, label, what it is, the players a server has online, the versions it takes,
 * Join (the launcher checks the server again, then Minecraft's own connection screen joins it) and its verified links (after
 * Minecraft's link confirmation). Same window on every Minecraft line.
 */
public final class FlintDiscoverScreen extends WindowScreen {
	private final DiscoverPage page;

	public FlintDiscoverScreen(@Nullable Object parent) {
		super("Discover", parent, 1320, 760);
		this.page = new DiscoverPage(this, this::openLink);
	}

	@Override
	protected Node content() {
		return page;
	}

	@Override
	protected void step() {
		page.step();
	}

	@Override
	protected void initialFocus(boolean keyboard) {
	}
}
