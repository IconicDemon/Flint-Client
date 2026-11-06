package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Skins;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 3D Skins in first person on Minecraft 1.21.1: your own sleeves get your skin's voxels when your hands are drawn. 1.21.1's
 * renderHand sets the whole model up first (which gives the sleeves their third-person voxels), so the hand's are set after.
 */
@Mixin(PlayerRenderer.class)
abstract class AvatarRendererMixin {
	@Inject(method = "renderHand", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/client/model/PlayerModel;setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V"))
	private void flint$hand(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve,
		CallbackInfo callback) {
		Mc263Skins.firstPerson((PlayerModel<?>) ((PlayerRenderer) (Object) this).getModel(), player.getSkin().texture());
	}
}
