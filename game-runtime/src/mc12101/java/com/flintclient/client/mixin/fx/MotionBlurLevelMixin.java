package com.flintclient.client.mixin.fx;

import com.flintclient.client.control.fx.MotionBlur;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Motion Blur's early pass ({@link MotionBlur#early}) between the terrain and the entities, the split Natural Motion Blur makes
 * in the level renderer: in Minecraft 1.21.1's {@code LevelRenderer.renderLevel}, at its {@code "entities"} profiler step, after
 * the solid, cutout-mipped and cutout terrain and before any entity or block entity is drawn. An allocation-free hook (a
 * condition that is always true). If another mod takes the call over, it isn't reached and the late pass blurs the whole frame
 * ({@link com.flintclient.client.control.fx.NmbTiming#lateMode}).
 */
@Mixin(LevelRenderer.class)
abstract class MotionBlurLevelMixin {
	@WrapWithCondition(method = "renderLevel", require = 0, at = @At(value = "INVOKE_STRING",
		target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=entities"))
	private boolean flint$motionBlurEarly(ProfilerFiller profiler, String name) {
		if (MotionBlur.wantsEarly()) {
			MotionBlur.early(Minecraft.getInstance().getMainRenderTarget());
		}
		return true;
	}
}
