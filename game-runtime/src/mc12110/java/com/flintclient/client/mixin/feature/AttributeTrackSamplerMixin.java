package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Time Changer: the time of day the client's sky, light and fog are worked out from. Minecraft 1.21.10 (no attribute
 * tracks yet) reads it from the client level's data, which only the client has: the integrated server and the world's
 * real time never see it. (The 1.21.11 name is kept: the shared mixin config lists it.)
 */
@Mixin(ClientLevel.ClientLevelData.class)
abstract class AttributeTrackSamplerMixin {
	@ModifyReturnValue(method = "getDayTime", at = @At("RETURN"))
	private long flint$timeChanger(long ticks) {
		return Mc263Features.skyTicks(ticks);
	}
}
