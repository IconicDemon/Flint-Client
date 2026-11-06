package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263FallingLeaves;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Falling Leaves: a leaf block's animation tick drops its leaves and snowflakes; a decaying one (single player) bursts. */
@Mixin(LeavesBlock.class)
abstract class FallingLeavesMixin {
	@Inject(method = "animateTick", at = @At("HEAD"))
	private void flint$leaves(BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo callback) {
		Mc263FallingLeaves.leafTick(state, level, pos, random);
	}

	@Inject(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
	private void flint$decay(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo callback) {
		Mc263FallingLeaves.decayed(state, pos.immutable());
	}
}
