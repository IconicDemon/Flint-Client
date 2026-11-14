package com.flintclient.client.compat.state;

import com.flintclient.client.cosmetics.platform.CosmeticsState;
import com.flintclient.client.cosmetics.platform.Worn;
import net.minecraft.client.resources.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * A player's render state as Flint's features read it (see {@link EntityRenderState}), with the room the other lines add to
 * Minecraft's by mixin: the 3D cosmetics the player wears this frame ({@link CosmeticsState}).
 */
public class AvatarRenderState extends HumanoidRenderState implements CosmeticsState {
	public int id;
	public @Nullable PlayerSkin skin;
	/** The cape's swing as 1.21.2 keeps it (CapeLayer's three angles, degrees), filled when the player is drawn. */
	public float capeFlap;
	public float capeLean;
	public float capeLean2;
	/** What 1.21.2 keeps of the player's movement (the 3D cosmetics' motion clip). */
	public boolean isFallFlying;
	public boolean isVisuallySwimming;
	public boolean isInWater;
	private @Nullable Worn worn;

	@Override
	public @Nullable Worn flint$worn() {
		return worn;
	}

	@Override
	public void flint$worn(@Nullable Worn worn) {
		this.worn = worn;
	}
}
