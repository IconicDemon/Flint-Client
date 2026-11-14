package com.flintclient.client.mixin.control;

import com.flintclient.client.control.platform.BuiltInResources;
import java.util.function.Consumer;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds Flint's built-in resource pack next to Minecraft's own built-in packs when Fabric API isn't there
 * to provide it ({@link BuiltInResources}). Runs on each resource pack scan (start-up and reloads). Minecraft 1.19.2's
 * client pack source hands each pack it finds straight to the repository (1.20.5 lists them by id first), so Flint's pack is
 * made and handed over at once; a pack that couldn't be made (null) is left out, as later versions leave it out.
 */
@Mixin(ClientPackSource.class)
abstract class ClientPackSourceMixin {
	@Inject(method = "loadPacks", at = @At("TAIL"))
	private void flint$offerBuiltInResources(Consumer<Pack> packs, Pack.PackConstructor constructor, CallbackInfo callback) {
		BuiltInResources.offer((id, factory) -> {
			Pack pack = factory.apply(id);
			if (pack != null) {
				packs.accept(pack);
			}
		});
	}
}
