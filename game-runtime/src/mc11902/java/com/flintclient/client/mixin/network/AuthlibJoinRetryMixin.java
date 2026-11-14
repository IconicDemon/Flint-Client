package com.flintclient.client.mixin.network;

import com.flintclient.client.network.JoinRetry;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Joining an online server retries when Mojang's session server can't be reached (see {@link JoinRetry}). Minecraft 1.19.2's
 * authlib (3.11) keeps {@code joinServer} in {@code YggdrasilMinecraftSessionService}, taking the player's whole profile. It
 * reports a session server it can't reach or that answers with an error page as "unavailable", which is retried; it keeps no
 * HTTP status, so a 429 that comes back as Mojang's own error text is reported at once, as before.
 */
@Mixin(value = YggdrasilMinecraftSessionService.class, remap = false)
abstract class AuthlibJoinRetryMixin {
	@WrapMethod(method = "joinServer(Lcom/mojang/authlib/GameProfile;Ljava/lang/String;Ljava/lang/String;)V", remap = false)
	private void flint$joinWithRetry(GameProfile profile, String authenticationToken, String serverId, Operation<Void> original) {
		JoinRetry.run(() -> original.call(profile, authenticationToken, serverId));
	}
}
