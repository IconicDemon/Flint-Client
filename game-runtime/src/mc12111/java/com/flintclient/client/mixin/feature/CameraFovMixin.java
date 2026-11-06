package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.flintclient.client.control.platform.Mc263Zoom;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Zoom: the field of view divided by the zoom. Minecraft 1.21.11 works out both the world's and the hand's in
 * GameRenderer.getFov (useFovSetting: the world's), where 26.x has Camera.calculateFov and calculateHudFov.
 */
@Mixin(GameRenderer.class)
abstract class CameraFovMixin {
	@ModifyReturnValue(method = "getFov", at = @At("RETURN"))
	private float flint$zoom(float fov, @Local(argsOnly = true) boolean useFovSetting) {
		return useFovSetting ? Mc263Features.fov(fov) : Mc263Zoom.hudFov(fov);
	}
}
