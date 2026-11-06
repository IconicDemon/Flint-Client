package com.flintclient.client.compat.state;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The block outline being drawn, as 1.21.9's {@code BlockOutlineRenderState} holds it. */
public record BlockOutlineRenderState(BlockPos pos, boolean isTranslucent, boolean highContrast, VoxelShape shape) {
}
