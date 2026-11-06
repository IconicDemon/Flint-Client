package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263FallingLeaves;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Falling Leaves' Leaves on block hit: starting to break a leaf block knocks a few leaves (and snowflakes) off. */
@Mixin(MultiPlayerGameMode.class)
abstract class FallingLeavesSpawnerMixin {
	@Inject(method = "startDestroyBlock", at = @At("HEAD"))
	private void flint$hit(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> callback) {
		Mc263FallingLeaves.hit(pos);
	}
}
