package com.flintclient.qa.legacy.mixin;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.network.PlayerInfo;
import net.minecraft.client.network.handler.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** QaBots: the connection's player list (1.8.9 lists every player it knows). Dev-only. */
@Mixin(ClientPlayNetworkHandler.class)
public interface ClientPlayNetworkHandlerAccessor {
	@Accessor("onlinePlayers")
	Map<UUID, PlayerInfo> flintqa$onlinePlayers();
}
