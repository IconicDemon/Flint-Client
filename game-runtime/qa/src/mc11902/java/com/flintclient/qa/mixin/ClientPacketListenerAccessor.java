package com.flintclient.qa.mixin;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * QaBots: the connection's player list (every player the server told about). Minecraft 1.19.2 lists every one of them in the
 * Tab list (unlisted players came in 1.19.3). Dev-only.
 */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {
	@Accessor("playerInfoMap")
	Map<UUID, PlayerInfo> flintqa$playerInfoMap();
}
