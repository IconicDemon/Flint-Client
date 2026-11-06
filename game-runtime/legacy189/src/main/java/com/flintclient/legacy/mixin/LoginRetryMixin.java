package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import com.flintclient.legacy.shared.LegacyJoin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import java.lang.reflect.Field;
import java.net.Proxy;
import java.net.URL;
import net.minecraft.client.network.handler.ClientLoginNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1.8.9's join with Mojang's session server goes through {@link LegacyJoin}: authlib 1.5.21 takes Mojang's frequent "503,
 * try again" for a join that went through (the server then can't confirm the player: "Failed to verify username!"), and
 * the first try of a burst fails within the same second as the next. {@link LegacyJoin} reads the status and waits a few
 * seconds between tries, as {@code JoinRetry} does on the other lines; a refused login is reported at once, as before.
 *
 * <p>1.8.9 loads authlib outside Fabric's class loader, so its session service can't be changed; the call to it in the
 * login handler is wrapped instead (both of {@code handleHello}'s calls). The request goes to the address authlib itself
 * would use (its {@code JOIN_URL}, read once), through authlib's proxy; a session service that isn't authlib's Yggdrasil
 * one, or an address that can't be read, keeps authlib's own join.
 */
@Mixin(ClientLoginNetworkHandler.class)
abstract class LoginRetryMixin {
	@Unique
	private static volatile URL flint$joinUrl;
	@Unique
	private static volatile boolean flint$joinUrlRead;

	@WrapOperation(
		method = "handleHello",
		at = @At(value = "INVOKE", target = "Lcom/mojang/authlib/minecraft/MinecraftSessionService;joinServer(Lcom/mojang/authlib/GameProfile;Ljava/lang/String;Ljava/lang/String;)V", remap = false))
	private void flint$joinWithRetry(MinecraftSessionService service, GameProfile profile, String authenticationToken, String serverId, Operation<Void> original) {
		URL url = service instanceof YggdrasilMinecraftSessionService ? flint$joinUrl() : null;
		if (url == null || profile == null || profile.getId() == null) {
			original.call(service, profile, authenticationToken, serverId);
			return;
		}
		Proxy proxy = ((YggdrasilMinecraftSessionService) service).getAuthenticationService().getProxy();
		if (proxy == null) {
			proxy = Proxy.NO_PROXY;
		}
		try {
			LegacyJoin.join(url, proxy, authenticationToken, profile.getId(), serverId, Flint.LOG::info);
		} catch (Throwable failure) {
			throw LoginRetryMixin.<RuntimeException>flint$sneaky(failure);
		}
	}

	/** authlib's join address ({@code YggdrasilMinecraftSessionService.JOIN_URL}), or null when it can't be read. */
	@Unique
	private static URL flint$joinUrl() {
		if (!flint$joinUrlRead) {
			URL url = null;
			try {
				Field field = YggdrasilMinecraftSessionService.class.getDeclaredField("JOIN_URL");
				field.setAccessible(true);
				Object value = field.get(null);
				url = value instanceof URL u && "https".equals(u.getProtocol()) ? u : null;
			} catch (ReflectiveOperationException | RuntimeException e) {
				Flint.LOG.warn("Flint: authlib's join address couldn't be read, so its own join is used ({})", e.toString());
			}
			flint$joinUrl = url;
			flint$joinUrlRead = true;
		}
		return flint$joinUrl;
	}

	@Unique
	@SuppressWarnings("unchecked")
	private static <T extends Throwable> T flint$sneaky(Throwable failure) throws T {
		throw (T) failure;
	}
}
