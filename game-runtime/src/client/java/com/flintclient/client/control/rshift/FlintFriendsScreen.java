package com.flintclient.client.control.rshift;

import com.flintclient.client.module.social.FriendsClient;
import com.flintclient.client.platform.Faces;
import com.flintclient.client.ui.kit.Node;
import org.jspecify.annotations.Nullable;

/**
 * Flint Friends in the game, a window of its own (as Daily Rewards is): the friends of the Flint app's Friends page, their
 * requests and chats, for the account the game plays as. Opened from Flint's main menu (its Friends icon);
 * everything it shows and does goes through the Flint app that started the game ({@link FriendsClient}): polled every 4 s
 * while it is open, actions run by the app as its own Friends page runs them. Same window on every Minecraft line.
 */
public final class FlintFriendsScreen extends WindowScreen {
	private final FriendsPage page;

	private FlintFriendsScreen(@Nullable Object parent) {
		super("Friends", parent, 1320, 720);
		this.page = new FriendsPage(FriendsClient.get(FlintFriendsScreen::playing), this::openLink,
			com.flintclient.client.platform.MenuHost.minecraftFriends() ? () -> com.flintclient.client.platform.MenuHost.openMinecraftFriends(this) : null);
	}

	/** The Friends window over {@code parent} (an engine screen, or null for the game). */
	public static FlintFriendsScreen friends(@Nullable Object parent) {
		return new FlintFriendsScreen(parent);
	}

	/** The account the game plays as. */
	public static @Nullable String playing() {
		try {
			return Faces.ownId();
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
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
