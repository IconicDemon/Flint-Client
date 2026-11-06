package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263FallingLeaves;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Falling Leaves' leaf spawners (Experimental): any block listed drops leaves as it is animated. */
@Mixin(Block.class)
abstract class FallingLeavesBlockMixin {
	@Inject(method = "animateTick", at = @At("HEAD"))
	private void flint$spawner(BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo callback) {
		Mc263FallingLeaves.blockTick(state, level, pos, random);
	}
}
