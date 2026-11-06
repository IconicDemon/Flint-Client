package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * UHC Overlay: a dropped item drawn at its size, right after Minecraft has placed it (its position, bob and spin), so it grows
 * in place and every copy of a stack grows with it.
 */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
	@Inject(method = "render(Lnet/minecraft/entity/ItemEntity;DDDFF)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/render/entity/ItemEntityRenderer;applyItemBobbing(Lnet/minecraft/entity/ItemEntity;DDDFLnet/minecraft/client/render/model/block/BakedModel;)I",
		shift = At.Shift.AFTER))
	private void flint$uhcScale(ItemEntity entity, double x, double y, double z, float yaw, float tickDelta, CallbackInfo ci) {
		if (!Flint.ready()) {
			return;
		}
		try {
			float scale = Flint.modules().uhcOverlay.scale(entity.getItem());
			if (scale != 1) {
				GlStateManager.scalef(scale, scale, scale);
			}
		} catch (RuntimeException | LinkageError e) {
			Flint.fault(Flint.modules().uhcOverlay, e);
		}
	}
}
