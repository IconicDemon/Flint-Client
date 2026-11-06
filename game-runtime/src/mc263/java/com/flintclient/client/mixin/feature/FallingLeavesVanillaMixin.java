package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263FallingLeaves;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingParticlesLeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Falling Leaves and Minecraft's own falling leaves (26.3: FallingParticlesLeavesBlock; earlier versions have a copy on
 * LeavesBlock): none when Flint drops its own, Falling Leaves' rate when the block keeps vanilla particles.
 */
@Mixin(FallingParticlesLeavesBlock.class)
abstract class FallingLeavesVanillaMixin implements Mc263FallingLeaves.VanillaLeaves {
	@Unique
	private BlockState flint$state;

	@Shadow
	protected abstract void spawnFallingLeavesParticle(Level level, BlockPos pos, RandomSource random);

	@Override
	public void flint$spawnVanillaLeaf(Level level, BlockPos pos, RandomSource random) {
		spawnFallingLeavesParticle(level, pos, random);
	}

	@Inject(method = "animateTick", at = @At("HEAD"))
	private void flint$state(BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo callback) {
		flint$state = state;
	}

	@ModifyExpressionValue(method = "makeFallingLeavesParticles", at = @At(value = "FIELD",
		target = "Lnet/minecraft/world/level/block/FallingParticlesLeavesBlock;leafParticleChance:F"))
	private float flint$chance(float vanilla, @Local(argsOnly = true, ordinal = 0) BlockPos pos) {
		return flint$state == null ? vanilla : Mc263FallingLeaves.vanillaChance(flint$state, pos, vanilla);
	}
}
