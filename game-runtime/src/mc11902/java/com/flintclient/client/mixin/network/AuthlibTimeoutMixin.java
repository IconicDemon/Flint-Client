package com.flintclient.client.mixin.network;

import java.net.HttpURLConnection;
import java.net.URL;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mojang's session server, which loads players' skins and lets a client join an online server, answered in 6-8 seconds about one
 * request in three on 2026-11-03 (see the shared AuthlibTimeoutMixin). Minecraft 1.19.2's authlib (3.11) asks the session server
 * (joins, profiles and skins) through {@code HttpAuthenticationService}, which already gives each request 15 seconds, and the
 * services API (signing keys, privileges, the block list) through {@code MinecraftClient}, which gives it 5 to connect and 5 to
 * answer: Flint gives those 15 too. Both build each request in {@code createUrlConnection}.
 *
 * <p>Each request also asks for its connection to be closed afterwards, so a join never goes out on a connection Java kept from
 * an earlier request that the server may have closed since (Java never retries a POST).
 */
@Mixin(targets = {"com.mojang.authlib.minecraft.client.MinecraftClient", "com.mojang.authlib.HttpAuthenticationService"}, remap = false)
abstract class AuthlibTimeoutMixin {
	private static final int SESSION_TIMEOUT_MS = 15_000;

	@ModifyConstant(method = "createUrlConnection", constant = @Constant(intValue = 5000), require = 0, remap = false)
	private int flint$patientSessionServer(int mojangDefault) {
		return Math.max(mojangDefault, SESSION_TIMEOUT_MS);
	}

	@Inject(method = "createUrlConnection", at = @At("RETURN"), require = 0, remap = false)
	private void flint$freshConnection(URL url, CallbackInfoReturnable<HttpURLConnection> cir) {
		HttpURLConnection connection = cir.getReturnValue();
		if (connection != null) {
			connection.setRequestProperty("Connection", "close");
		}
	}
}
