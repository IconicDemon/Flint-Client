package com.flintclient.client.mixin.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Minecraft's friend list updates (26.2 on), made for the signed-in user: the account card replaces them on a switch. */
@Mixin(Minecraft.class)
public interface MinecraftFriendsAccessor {
	@Accessor("remoteFriendListUpdateHandler")
	RemoteFriendListUpdateHandler flint$friendListUpdates();

	@Mutable
	@Accessor("remoteFriendListUpdateHandler")
	void flint$setFriendListUpdates(RemoteFriendListUpdateHandler handler);
}
