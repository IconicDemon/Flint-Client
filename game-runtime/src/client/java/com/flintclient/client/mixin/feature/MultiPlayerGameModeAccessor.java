package com.flintclient.client.mixin.feature;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Attack Indicator and Block Indicator: the block being mined and how far it is (0 to 1), as Minecraft counts it. */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
	@Accessor("destroyProgress")
	float flint$destroyProgress();

	@Accessor("destroyBlockPos")
	net.minecraft.core.BlockPos flint$destroyBlockPos();
}
