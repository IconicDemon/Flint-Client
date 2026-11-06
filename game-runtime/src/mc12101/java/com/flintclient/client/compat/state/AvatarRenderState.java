package com.flintclient.client.compat.state;

import net.minecraft.client.resources.PlayerSkin;
import org.jspecify.annotations.Nullable;

/** A player's render state as Flint's features read it (see {@link EntityRenderState}). */
public class AvatarRenderState extends HumanoidRenderState {
	public int id;
	public @Nullable PlayerSkin skin;
	/** The cape's swing as 1.21.2 keeps it (CapeLayer's three angles, degrees), filled when the player is drawn. */
	public float capeFlap;
	public float capeLean;
	public float capeLean2;
}
