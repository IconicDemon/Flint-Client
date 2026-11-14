package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.CosmeticsState;
import com.flintclient.client.cosmetics.platform.Worn;
import net.minecraft.client.player.AbstractClientPlayer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * A player carries what they wear for the 3D cosmetics ({@link CosmeticsState}) on Minecraft 1.19, which has no render
 * states: set as the player starts to be drawn, read by the layers and the model while it is.
 */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerCosmeticsMixin implements CosmeticsState {
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
