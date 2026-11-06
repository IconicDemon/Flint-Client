package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.rshift.FlintFriendsScreen;
import com.flintclient.client.module.account.LauncherLink;
import com.flintclient.client.module.social.FriendsClient;
import net.minecraft.client.Minecraft;

/**
 * Flint Friends' notices in game: a friend's message, a friend request, a request accepted, a friend coming online, each as
 * one of Flint's small toasts ({@link Mc263NoticeToast}: no message text, only who and what). While playing, with Friends
 * notifications on (the Friends window's settings, {@link GameOptions#FRIEND_NOTICES}), the Flint app is asked every 15 s
 * ({@link FriendsClient}); never without the game link, and never while the Friends window is open (it shows them already).
 * Fault-isolated: a failure turns the notices off for the session, never the game.
 */
public final class Mc263FriendNotices {
	private static final HookGuard GUARD = new HookGuard("Friends notices",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));

	private Mc263FriendNotices() {
	}

	/** Every client tick. */
	public static void tick() {
		if (!GUARD.on() || LauncherLink.get() == null) {
			return;
		}
		try {
			Minecraft minecraft = Minecraft.getInstance();
			boolean on = ControlState.settings().gameBool(GameOptions.FRIEND_NOTICES);
			FriendsClient client = FriendsClient.existing();
			if (client == null) {
				if (!on || minecraft.level == null) {
					return;
				}
				client = FriendsClient.get(FlintFriendsScreen::playing);
			}
			if (on && minecraft.level != null) {
				client.want();
			}
			client.tick();
			var notices = client.takeNotices();
			if (!on || minecraft.gui.screen() instanceof FlintFriendsScreen) {
				return;
			}
			for (FriendsClient.Notice notice : notices) {
				Mc263NoticeToast.show(notice.text());
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}
}
