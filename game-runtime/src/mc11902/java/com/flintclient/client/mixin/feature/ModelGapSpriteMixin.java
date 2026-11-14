/*
 * Adapted from Model Gap Fix by MehVahdJukaar (https://github.com/MehVahdJukaar/modelfix-multi), GPL-3.0: the licence text is
 * in META-INF/licenses/GPL-3.0-ModelGapFix.txt. This file is covered by the GPL-3.0.
 */
package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ModelFix;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Model Gap Fix's main fix on 1.19.2: a block-atlas sprite's UVs aren't shrunk toward its centre (the shrink that cuts the
 * edges of block textures and opens gaps in item models); on macOS the shrink is only multiplied down. A 1.19.2 sprite knows
 * its atlas itself (later versions only the atlas's name).
 */
@Mixin(TextureAtlasSprite.class)
abstract class ModelGapSpriteMixin {
	@Shadow
	private float atlasSize() {
		throw new AssertionError();
	}

	@Shadow
	public abstract TextureAtlas atlas();

	@Inject(method = "uvShrinkRatio", at = @At("RETURN"), cancellable = true)
	private void flint$noShrink(CallbackInfoReturnable<Float> callback) {
		Mc263ModelFix.Params p = Mc263ModelFix.baking();
		if (!p.on()) {
			return;
		}
		float vanilla = 4.0F / atlasSize();
		if (TextureAtlas.LOCATION_BLOCKS.equals(atlas().location()) && vanilla == callback.getReturnValueF()) {
			callback.setReturnValue((float) (vanilla * p.shrink()));
		}
	}
}
