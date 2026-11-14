package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.state.AvatarRenderState;
import com.flintclient.client.compat.state.RenderStates;
import com.flintclient.client.control.platform.CustomPart;
import com.flintclient.client.control.platform.Mc263Capes;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wavy Capes on Minecraft 1.19.2: right before a cape is drawn, the cloth takes its box's place. 1.19.2 draws the cape in
 * CapeLayer, leaning it on the pose (three turns, Minecraft's own quaternions) before the player model's cloak part; while
 * Flint's cloth is drawn it hangs and bends itself, so only the turn that faces it backwards is kept (as later versions keep
 * the part's own).
 */
@Mixin(CapeLayer.class)
abstract class PlayerCapeModelMixin extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
	@Unique
	private static final String RENDER = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V";
	@Unique
	private static final String MUL_POSE = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lcom/mojang/math/Quaternion;)V";
	/** Facing backwards, the cloth's only turn here (mulPose doesn't change the quaternion it is given). */
	@Unique
	private static final Quaternion FLINT$BACKWARDS = Vector3f.YP.rotationDegrees(180.0F);

	/** Whether this cape is Flint's cloth (set as the layer begins, for the turns that follow). */
	@Unique
	private boolean flint$cloth;

	PlayerCapeModelMixin(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
		super(parent);
	}

	@Inject(method = RENDER, at = @At("HEAD"))
	private void flint$wavyCape(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float a, float b, float c, float d,
		float e, float f, CallbackInfo callback) {
		ModelPart cloak = ((com.flintclient.client.mixin.bridge.PlayerModelCloakAccessor) getParentModel()).flint$cloak();
		flint$cloth = false;
		if (RenderStates.peek(player) instanceof AvatarRenderState state) {
			Mc263Capes.setup(cloak, state);
			flint$cloth = ((CustomPart) (Object) cloak).flint$drawing() != null;
		} else {
			((CustomPart) (Object) cloak).flint$drawing(null);
		}
		// 1.19.2's cloak part has no turn of its own (the layer turns the pose); the cloth's setup gives it one.
		cloak.setRotation(0, 0, 0);
	}

	@WrapWithCondition(method = RENDER, at = @At(value = "INVOKE", ordinal = 0, target = MUL_POSE))
	private boolean flint$noLean(PoseStack pose, Quaternion turn) {
		return !flint$cloth;
	}

	@WrapWithCondition(method = RENDER, at = @At(value = "INVOKE", ordinal = 1, target = MUL_POSE))
	private boolean flint$noSideLean(PoseStack pose, Quaternion turn) {
		return !flint$cloth;
	}

	@WrapOperation(method = RENDER, at = @At(value = "INVOKE", ordinal = 2, target = MUL_POSE))
	private void flint$backwards(PoseStack pose, Quaternion turn, Operation<Void> original) {
		original.call(pose, flint$cloth ? FLINT$BACKWARDS : turn);
	}
}
