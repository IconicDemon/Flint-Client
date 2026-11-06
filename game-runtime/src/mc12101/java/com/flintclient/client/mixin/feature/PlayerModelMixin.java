package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.state.AvatarRenderState;
import com.flintclient.client.compat.state.RenderStates;
import com.flintclient.client.control.platform.Mc263Animations;
import com.flintclient.client.control.platform.Mc263Skins;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Animations and 3D Skins on Minecraft 1.21.1, which sets the player model up from the entity (the player's state,
 * compat.state, was filled as its renderer began). Animations: a crouching climber or a glider is drawn standing (1.21.1's
 * model reads its own crouching flag, set from the state here), and Not Enough Animations' poses go on after Minecraft's
 * own. 3D Skins: the outer layers get that skin's voxels (or Minecraft's flat boxes back). The first-person hand sets its
 * own sleeves (AvatarRendererMixin).
 */
@Mixin(PlayerModel.class)
abstract class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
	private void flint$animationsBefore(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float headYaw,
		float headPitch, CallbackInfo callback) {
		if (RenderStates.peek(entity) instanceof AvatarRenderState state && state.skin != null) {
			state.isCrouching = entity.isCrouching();
			Mc263Animations.before(state);
			if (!state.isCrouching) {
				((PlayerModel<?>) (Object) this).crouching = false;
			}
		}
	}

	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
	private void flint$voxels(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float headYaw, float headPitch,
		CallbackInfo callback) {
		if (RenderStates.peek(entity) instanceof AvatarRenderState state && state.skin != null) {
			Mc263Animations.after(state, (PlayerModel) (Object) this);
			Mc263Skins.apply((PlayerModel) (Object) this, state);
		}
	}
}
