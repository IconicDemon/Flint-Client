package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263LookMods;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Mob Overlay's Outline: the mobs it covers glow, with Minecraft's own outline. */
@Mixin(Minecraft.class)
abstract class MobGlowMixin {
	@ModifyReturnValue(method = "shouldEntityAppearGlowing", at = @At("RETURN"))
	private boolean flint$mobs(boolean glowing, Entity entity) {
		return Mc263LookMods.glowing(entity, glowing);
	}
}
