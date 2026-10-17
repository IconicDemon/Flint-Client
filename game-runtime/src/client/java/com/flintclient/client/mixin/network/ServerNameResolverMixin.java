package com.flintclient.client.mixin.network;

import com.flintclient.client.network.platform.NetworkHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.Optional;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Times the address lookup (A/AAAA, blocklist, SRV). Status pings run through here too, several per menu
 * visit, so both hooks pass values through unchanged instead of allocating callback info; the tracker
 * ignores every thread but the tracked attempt's connector.
 */
@Mixin(ServerNameResolver.class)
abstract class ServerNameResolverMixin {
	@ModifyVariable(method = "resolveAddress", at = @At("HEAD"), argsOnly = true)
	private ServerAddress flint$resolving(ServerAddress address) {
		NetworkHooks.resolving();
		return address;
	}

	// Empty means vanilla shows "unknown host" (also for a blocked server).
	@ModifyReturnValue(method = "resolveAddress", at = @At("RETURN"))
	private Optional<ResolvedServerAddress> flint$resolved(Optional<ResolvedServerAddress> resolved) {
		NetworkHooks.resolved(resolved.isPresent());
		return resolved;
	}
}
