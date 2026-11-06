package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Hitbox;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.BeeStingerLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hitbox's Hide stuck arrows (combat-hitbox): players show no stuck arrows or bee stingers. */
@Mixin({ArrowLayer.class, BeeStingerLayer.class})
abstract class StuckInBodyMixin {
	@ModifyReturnValue(method = "numStuck", at = @At("RETURN"))
	private int flint$hideStuck(int count) {
		return Mc263Hitbox.stuck(count);
	}
}
