package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.CosmeticsState;
import com.flintclient.client.cosmetics.platform.EmoteModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An emote playing on a player takes over the parts it animates, after Minecraft (and Flint's animations) posed the model. */
@Mixin(value = PlayerModel.class, priority = 1100)
abstract class PlayerModelEmoteMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
	private void flint$emote(LivingEntity entity, float limbSwing, float limbSwingAmount, float age, float headYaw, float headPitch,
		CallbackInfo callback) {
		if (entity instanceof CosmeticsState state) {
			EmoteModel.apply((PlayerModel<?>) (Object) this, state.flint$worn());
		}
	}
}
