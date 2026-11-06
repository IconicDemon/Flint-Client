package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.entity.PrimedTntRenderer;
import net.minecraft.entity.PrimedTntEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TNT Countdown: the timer above a lit TNT, right after Minecraft has drawn the block (and its nametag, if it has one). */
@Mixin(PrimedTntRenderer.class)
public abstract class PrimedTntRendererMixin {
	@Inject(method = "render(Lnet/minecraft/entity/PrimedTntEntity;DDDFF)V", at = @At("TAIL"))
	private void flint$timer(PrimedTntEntity tnt, double x, double y, double z, float yaw, float tickDelta, CallbackInfo ci) {
		if (!Flint.ready()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			Flint.modules().tntTimer.draw(tnt, x, y, z, tickDelta, mc.getEntityRenderDispatcher(), mc.textRenderer);
		} catch (RuntimeException | LinkageError e) {
			Flint.fault(Flint.modules().tntTimer, e);
		}
	}
}
