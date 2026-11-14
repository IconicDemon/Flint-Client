package com.flintclient.client.cosmetics.platform;

import org.jspecify.annotations.Nullable;

/**
 * What a player's render state carries for the 3D cosmetics (added to Minecraft's {@code AvatarRenderState} by
 * {@code mixin/cosmetics/AvatarRenderStateCosmeticsMixin}), set when the state is extracted from the player
 * ({@link Mc263Cosmetics#extract}) and read when it is drawn.
 */
public interface CosmeticsState {
	/** The wearer's cosmetics this frame (null: none). */
	@Nullable Worn flint$worn();

	void flint$worn(@Nullable Worn worn);
}
