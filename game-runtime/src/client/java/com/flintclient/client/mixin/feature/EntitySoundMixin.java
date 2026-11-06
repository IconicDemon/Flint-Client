package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.sound.SoundPhysics;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Sound Physics: an entity's sounds come from its eyes rather than its feet (footsteps stay at the feet), so their rays
 * start where a voice would. Adapted from Sound Physics Remastered's {@code EntityMixin} (GPL-3.0).
 */
@Mixin(Entity.class)
abstract class EntitySoundMixin {
	@ModifyArg(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"),
		index = 2)
	private double flint$eyeHeight(@Nullable Entity except, double x, double y, double z, SoundEvent sound, SoundSource category, float volume,
		float pitch) {
		return sound == null ? y : SoundPhysics.entitySoundY(y, ((Entity) (Object) this).getEyeHeight(), sound.location());
	}
}
