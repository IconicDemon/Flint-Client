package com.flintclient.client.mixin.ncr;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * No Chat Reports' conversion in the world this client hosts: the player chat index a converted message took is given back,
 * so a player who later gets real player chat (the conversion turned off mid-session) sees no gap in it.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface NcrServerGameListenerAccessor {
	@Accessor("nextChatIndex")
	void flint$setNextChatIndex(int index);
}
