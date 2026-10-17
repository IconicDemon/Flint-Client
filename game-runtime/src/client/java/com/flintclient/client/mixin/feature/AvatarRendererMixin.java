package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Skins;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 3D Skins in first person: your own sleeves get your skin's voxels when your hands are drawn. */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
	@Inject(method = "renderHand", at = @At("HEAD"))
	private void flint$hand(PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin, ModelPart arm, boolean hasSleeve,
		CallbackInfo callback) {
		Mc263Skins.firstPerson((PlayerModel) ((AvatarRenderer<?>) (Object) this).getModel(), skin);
	}
}
