package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ChatHeads;
import java.util.List;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chat Heads: a server whose resource pack carries chat_heads:disable gets no heads (Chat Heads' own opt-out, obeyed). */
@Mixin(DownloadedPackSource.class)
abstract class ChatHeadsServerMixin {
	@Inject(method = "loadRequestedPacks", at = @At("RETURN"))
	private void flint$serverOptOut(CallbackInfoReturnable<List<Pack>> callback) {
		List<Pack> packs = callback.getReturnValue();
		if (packs == null) {
			return;
		}
		for (Pack pack : packs) {
			try {
				// One resource set (1.21.x, 26.1, 26.2), or a stream of them (26.3).
				Object opened = pack.open();
				if (opened instanceof PackResources resources) {
					check(resources);
				} else if (opened instanceof java.util.stream.Stream<?> stream) {
					stream.forEach(r -> check((PackResources) r));
				}
			} catch (RuntimeException | LinkageError e) {
				// A pack that can't be read can't ask either.
			}
		}
	}

	private static void check(PackResources resources) {
		try (resources) {
			if (resources.getResource(PackType.CLIENT_RESOURCES, Mc263ChatHeads.DISABLE_RESOURCE) != null) {
				Mc263ChatHeads.disabledByServer();
			}
		}
	}
}
