package com.flintclient.client.mixin.control;

import com.flintclient.client.control.platform.BuiltInResources;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds Flint's built-in resource pack next to Minecraft's own built-in packs when Fabric API isn't there
 * to provide it ({@link BuiltInResources}). Runs on each resource pack scan (start-up and reloads).
 */
@Mixin(ClientPackSource.class)
abstract class ClientPackSourceMixin {
	@Inject(method = "populatePackList", at = @At("TAIL"))
	private void flint$offerBuiltInResources(BiConsumer<String, Function<String, Pack>> discoveredPacks, CallbackInfo callback) {
		BuiltInResources.offer(discoveredPacks);
	}
}
