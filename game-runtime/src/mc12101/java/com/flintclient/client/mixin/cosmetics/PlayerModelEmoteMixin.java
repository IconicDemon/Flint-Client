package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.compat.state.AvatarRenderState;
import com.flintclient.client.compat.state.RenderStates;
import com.flintclient.client.cosmetics.platform.EmoteModel;
import com.flintclient.client.cosmetics.platform.Mc263Cosmetics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Once 1.21.1 has posed a player's model (every time the player is drawn: the world, the inventory doll, the first-person hand):
 * what they wear and do this frame onto their render state (Flint's stand-in, filled as the player is drawn), then an emote
 * playing on them laid over the pose ({@link EmoteModel}). After Flint's other pose hooks (priority).
 */
@Mixin(value = PlayerModel.class, priority = 1100)
abstract class PlayerModelEmoteMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
	private void flint$emote(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float headYaw, float headPitch,
		CallbackInfo callback) {
		if (RenderStates.peek(entity) instanceof AvatarRenderState state) {
			Mc263Cosmetics.extract(entity, state);
			EmoteModel.apply((PlayerModel<?>) (Object) this, state);
		}
	}
}
