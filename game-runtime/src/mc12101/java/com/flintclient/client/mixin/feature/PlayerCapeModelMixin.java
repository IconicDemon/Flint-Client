package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.state.AvatarRenderState;
import com.flintclient.client.compat.state.RenderStates;
import com.flintclient.client.control.platform.CustomPart;
import com.flintclient.client.control.platform.Mc263Capes;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wavy Capes on Minecraft 1.21.1: right before a cape is drawn, the cloth takes its box's place. 1.21.1 draws the cape in
 * CapeLayer, leaning it on the pose (three turns) before the player model's cloak part; while Flint's cloth is drawn it hangs
 * and bends itself, so only the turn that faces it backwards is kept (as later versions keep the part's own).
 */
@Mixin(CapeLayer.class)
abstract class PlayerCapeModelMixin extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
	private static final String RENDER = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V";

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
		// 1.21.1's cloak part has no turn of its own (the layer turns the pose); the cloth's setup gives it one.
		cloak.setRotation(0, 0, 0);
	}

	@WrapWithCondition(method = RENDER, at = @At(value = "INVOKE", ordinal = 0, target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V"))
	private boolean flint$noLean(PoseStack pose, Quaternionf turn) {
		return !flint$cloth;
	}

	@WrapWithCondition(method = RENDER, at = @At(value = "INVOKE", ordinal = 1, target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V"))
	private boolean flint$noSideLean(PoseStack pose, Quaternionf turn) {
		return !flint$cloth;
	}

	@WrapOperation(method = RENDER, at = @At(value = "INVOKE", ordinal = 2, target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V"))
	private void flint$backwards(PoseStack pose, Quaternionf turn, Operation<Void> original) {
		original.call(pose, flint$cloth ? Axis.YP.rotationDegrees(180.0F) : turn);
	}

}
