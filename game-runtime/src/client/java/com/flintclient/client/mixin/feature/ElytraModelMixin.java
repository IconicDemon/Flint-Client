package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.CapeWingsState;
import com.flintclient.client.control.platform.Mc263CapeElytra;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The cape-derived elytra: right after Minecraft sets the elytra model up for a player (its wing angles and the crouch offset,
 * as it draws them), its two wing parts draw the cape's wing mesh when the elytra layer chose it for that player
 * ({@link CapeWingsState}, {@link Mc263CapeElytra}), else their boxes. The model is shared, so it is set every time.
 */
@Mixin(ElytraModel.class)
abstract class ElytraModelMixin {
	@Shadow
	@Final
	private ModelPart leftWing;
	@Shadow
	@Final
	private ModelPart rightWing;

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
	private void flint$capeWings(HumanoidRenderState state, CallbackInfo callback) {
		Mc263CapeElytra.setup(leftWing, rightWing, state instanceof CapeWingsState wings && wings.flint$capeWings());
	}
}
