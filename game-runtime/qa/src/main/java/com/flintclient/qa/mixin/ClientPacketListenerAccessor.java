package com.flintclient.qa.mixin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** QaBots: the connection's player list (every player the server told about, and the ones it lists). Dev-only. */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {
	@Accessor("playerInfoMap")
	Map<UUID, PlayerInfo> flintqa$playerInfoMap();

	@Accessor("listedPlayers")
	Set<PlayerInfo> flintqa$listedPlayers();
}
