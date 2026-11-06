package com.flintclient.client.mixin.fx;

import com.flintclient.client.control.fx.MotionBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Motion Blur's early pass ({@link MotionBlur#early}) between the solid terrain and the solid features (players, mobs, block
 * entities), the split Natural Motion Blur makes in the level renderer. This Minecraft draws the opaque terrain in a render pass
 * of its own ({@code renderGroup(OPAQUE, …)}, called from the level renderer's main pass before the features), so the blur runs
 * as that returns, with no pass open. An allocation-free hook (a variable handed back unchanged, no callback). If another mod
 * draws the terrain instead, it isn't reached and the late pass blurs the whole frame
 * ({@link com.flintclient.client.control.fx.NmbTiming#lateMode}).
 */
@Mixin(ChunkSectionsToRender.class)
abstract class MotionBlurLevelMixin {
	@ModifyVariable(method = "renderGroup", require = 0, at = @At("RETURN"), argsOnly = true)
	private ChunkSectionLayerGroup flint$motionBlurEarly(ChunkSectionLayerGroup group) {
		if (group == ChunkSectionLayerGroup.OPAQUE && MotionBlur.wantsEarly()) {
			MotionBlur.early(Minecraft.getInstance().getMainRenderTarget());
		}
		return group;
	}
}
