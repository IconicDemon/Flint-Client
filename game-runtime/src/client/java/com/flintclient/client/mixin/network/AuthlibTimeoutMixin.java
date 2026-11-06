package com.flintclient.client.mixin.network;

import java.net.HttpURLConnection;
import java.net.URL;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mojang's session server, which loads players' skins and lets a client join an online server, answered in 6-8 seconds
 * about one request in three on 2026-11-03 (measured from the owner's PC). Mojang's authlib gives each request 5 seconds
 * to connect and 5 to answer, so the player's own skin went missing for the whole session (its one lookup at start-up
 * timed out) and joining servers failed every few tries. Flint gives the session server 15 seconds for each, the same as
 * the older authlib 1.8.9 uses. Nothing waits on it that didn't already: the skin lookup runs in the background, and a join
 * shows its connecting screen with Cancel. authlib 6 and newer build every request in {@code createUrlConnection}.
 *
 * <p>Each request also asks for its connection to be closed afterwards, so a join never goes out on a connection Java kept
 * from an earlier request that the server may have closed since (Java never retries a POST). The cost is one new secure
 * connection per request, a few of which a game makes. (The "not reachable" joins of 2026-11-03/04 turned out to be
 * Mojang's own 503s: see {@code JoinRetry}.)
 */
@Pseudo
@Mixin(targets = "com.mojang.authlib.minecraft.client.MinecraftClient", remap = false)
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
