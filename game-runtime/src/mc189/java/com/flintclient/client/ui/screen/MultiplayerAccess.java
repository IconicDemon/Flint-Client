package com.flintclient.client.ui.screen;

import com.flintclient.client.mc.Component;
import com.flintclient.client.mc.Minecraft;
import org.jspecify.annotations.Nullable;

/** Why multiplayer is off for this account: Minecraft 1.8.9 never turns it off (no bans or parental settings it checks). */
final class MultiplayerAccess {
	private MultiplayerAccess() {
	}

	static @Nullable Component disabledReason(Minecraft minecraft) {
		return null;
	}
}
