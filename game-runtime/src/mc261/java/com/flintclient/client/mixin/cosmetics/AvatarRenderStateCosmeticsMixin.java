package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.CosmeticsState;
import com.flintclient.client.cosmetics.platform.Worn;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** A player's render state carries what they wear for the 3D cosmetics ({@link CosmeticsState}). */
@Mixin(AvatarRenderState.class)
abstract class AvatarRenderStateCosmeticsMixin implements CosmeticsState {
	@Unique
	private @Nullable Worn flint$worn;

	@Override
	public @Nullable Worn flint$worn() {
		return flint$worn;
	}

	@Override
	public void flint$worn(@Nullable Worn worn) {
		flint$worn = worn;
	}
}
