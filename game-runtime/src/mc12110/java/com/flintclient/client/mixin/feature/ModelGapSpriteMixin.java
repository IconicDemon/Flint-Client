/*
 * Adapted from Model Gap Fix by MehVahdJukaar (https://github.com/MehVahdJukaar/modelfix-multi), GPL-3.0: the licence text is
 * in META-INF/licenses/GPL-3.0-ModelGapFix.txt. This file is covered by the GPL-3.0.
 */
package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ModelFix;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Model Gap Fix's main fix on 1.21.10: a block-atlas sprite's UVs aren't shrunk toward its centre (the shrink that cuts
 * the edges of block textures and opens gaps in item models); on macOS the shrink is only multiplied down.
 */
@Mixin(TextureAtlasSprite.class)
abstract class ModelGapSpriteMixin {
	@org.spongepowered.asm.mixin.Unique
	private static final ResourceLocation FLINT_BLOCK_ATLAS = ResourceLocation.withDefaultNamespace("textures/atlas/blocks.png");

	@Shadow
	private float atlasSize() {
		throw new AssertionError();
	}

	@Shadow
	public abstract ResourceLocation atlasLocation();

	@Inject(method = "uvShrinkRatio", at = @At("RETURN"), cancellable = true)
	private void flint$noShrink(CallbackInfoReturnable<Float> callback) {
		Mc263ModelFix.Params p = Mc263ModelFix.baking();
		if (!p.on()) {
			return;
		}
		float vanilla = 4.0F / atlasSize();
		if (atlasLocation().equals(FLINT_BLOCK_ATLAS) && vanilla == callback.getReturnValueF()) {
			callback.setReturnValue((float) (vanilla * p.shrink()));
		}
	}
}
