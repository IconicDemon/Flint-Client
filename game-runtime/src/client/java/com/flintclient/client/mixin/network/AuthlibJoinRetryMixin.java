package com.flintclient.client.mixin.network;

import com.flintclient.client.network.JoinRetry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Joining an online server retries when Mojang's session server can't be reached (see {@link JoinRetry}). 26.x's authlib
 * keeps {@code joinServer} in {@code MinecraftServicesSessionService}, 1.21's in {@code YggdrasilMinecraftSessionService};
 * each version has one of them, hence {@link Pseudo}.
 */
@Pseudo
@Mixin(targets = {"com.mojang.authlib.services.MinecraftServicesSessionService", "com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService"}, remap = false)
abstract class AuthlibJoinRetryMixin {
	@WrapMethod(method = "joinServer(Ljava/util/UUID;Ljava/lang/String;Ljava/lang/String;)V", remap = false)
	private void flint$joinWithRetry(UUID profileId, String authenticationToken, String serverId, Operation<Void> original) {
		JoinRetry.run(() -> original.call(profileId, authenticationToken, serverId));
	}
}
