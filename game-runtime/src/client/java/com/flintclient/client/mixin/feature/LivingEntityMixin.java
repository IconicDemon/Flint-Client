package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Combat feedback from what the server tells the client: damage events (a hit of yours landing names you as the cause)
 * and deaths (entity event 3). Client-side entities only; the integrated server's copies never reach these methods'
 * client paths because only {@code ClientPacketListener} calls them on the client.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void flint$damaged(DamageSource source, CallbackInfo callback) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide()) {
			Mc263Features.damaged(self, source);
		}
	}

	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void flint$event(byte id, CallbackInfo callback) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide()) {
			Mc263Features.entityEvent(self, id);
		}
	}
}
