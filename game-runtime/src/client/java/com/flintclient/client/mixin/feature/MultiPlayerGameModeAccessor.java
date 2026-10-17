package com.flintclient.client.mixin.feature;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Attack Indicator: how far the block being mined is (0 to 1), as Minecraft counts it. */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
	@Accessor("destroyProgress")
	float flint$destroyProgress();
}
