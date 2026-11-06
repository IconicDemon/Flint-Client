package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.state.RenderStates;
import com.flintclient.client.compat.state.SubmitNodeCollector;
import com.flintclient.client.control.platform.Mc263Overlay;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.TntRenderer;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TNT Countdown on 1.21.1: the timer above a lit TNT, right after Minecraft has drawn the block. (1.21.1's EntityRenderer.render
 * returns early for an entity without a nametag, so its tail never runs for TNT; later versions draw the timer from there.)
 */
@Mixin(TntRenderer.class)
abstract class TntRendererMixin {
	@Inject(method = "render(Lnet/minecraft/world/entity/item/PrimedTnt;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("TAIL"), require = 1)
	private void flint$timer(PrimedTnt tnt, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo callback) {
		Mc263Overlay.tntLabel(tnt.getFuse() - partialTick, tnt.getBbHeight(), tnt.distanceToSqr(RenderStates.camera().pos), pose,
			new SubmitNodeCollector(buffers), RenderStates.camera());
	}
}
