package com.flintclient.client.ui.screen;

import com.mojang.authlib.minecraft.BanDetails;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Why multiplayer is unavailable for this account, worded exactly as Minecraft's title screen does. */
final class MultiplayerAccess {
	private MultiplayerAccess() {
	}

	static @Nullable Component disabledReason(Minecraft minecraft) {
		if (minecraft.allowsMultiplayer()) {
			return null;
		}
		if (minecraft.isNameBanned()) {
			return Component.translatable("title.multiplayer.disabled.banned.name");
		}
		BanDetails ban = minecraft.multiplayerBan();
		if (ban != null) {
			return ban.expires() != null
				? Component.translatable("title.multiplayer.disabled.banned.temporary")
				: Component.translatable("title.multiplayer.disabled.banned.permanent");
		}
		return Component.translatable("title.multiplayer.disabled");
	}
}
