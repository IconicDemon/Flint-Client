package com.flintclient.client.cosmetics.platform;

import org.jspecify.annotations.Nullable;

/**
 * What a player carries for the 3D cosmetics on 1.19 (added to {@code AbstractClientPlayer} by
 * {@code mixin/cosmetics/AbstractClientPlayerCosmeticsMixin}: 1.19 has no render states), set as the player starts to be
 * drawn ({@link Mc263Cosmetics#extract}) and read while it is.
 */
public interface CosmeticsState {
	/** The wearer's cosmetics this frame (null: none). */
	@Nullable Worn flint$worn();

	void flint$worn(@Nullable Worn worn);
}
