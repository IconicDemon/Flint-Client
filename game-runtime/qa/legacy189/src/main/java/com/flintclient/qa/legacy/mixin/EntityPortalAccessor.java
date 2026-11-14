package com.flintclient.qa.legacy.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where an entity last stood in a portal: the QA dimension step sets it, as walking into a portal would, before the trip. */
@Mixin(Entity.class)
public interface EntityPortalAccessor {
	@Accessor("lastPortalPos")
	void flintqa$setLastPortalPos(BlockPos pos);

	@Accessor("lastPortalOffset")
	void flintqa$setLastPortalOffset(Vec3d offset);

	@Accessor("lastPortalFacing")
	void flintqa$setLastPortalFacing(Direction facing);
}
