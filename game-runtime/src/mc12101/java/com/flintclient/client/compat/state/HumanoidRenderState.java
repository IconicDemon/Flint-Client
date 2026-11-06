package com.flintclient.client.compat.state;

/** A humanoid's render state as Flint's features read it (see {@link EntityRenderState}). */
public class HumanoidRenderState extends LivingEntityRenderState {
	public boolean isCrouching;
	/** How far into swimming (0 to 1) and through an attack swing (0 to 1), as 1.21.2 keeps them. */
	public float swimAmount;
	public float attackTime;
}
