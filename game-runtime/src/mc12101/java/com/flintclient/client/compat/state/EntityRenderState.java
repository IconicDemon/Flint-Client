package com.flintclient.client.compat.state;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What 1.21.2's entity render state holds that Flint's features read. Minecraft 1.21.1 draws entities straight from the
 * game, so Flint's 1.21.1 hooks fill one of these from the entity as it is drawn and hand it to the same feature code.
 */
public class EntityRenderState {
	public @Nullable EntityType<?> entityType;
	public double x;
	public double y;
	public double z;
	public float ageInTicks;
	public float boundingBoxWidth;
	public float boundingBoxHeight;
	public double distanceToCameraSq;
	public boolean isInvisible;
	public boolean isDiscrete;
	public int lightCoords = 0xF000F0;
	public @Nullable Component nameTag;
	public @Nullable Vec3 nameTagAttachment;
}
