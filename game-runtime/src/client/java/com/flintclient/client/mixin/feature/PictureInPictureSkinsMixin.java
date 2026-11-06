package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Skins;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 3D Skins: while a menu picture of an entity (the inventory, Flint's player model) is drawn, how big its pixels are, so
 * a small one keeps the flat layers whose sides couldn't be seen anyway. The picture and GUI scale are taken by type,
 * because 26.2 and 26.3 pass a feature dispatcher between them and 26.1 doesn't.
 */
@Mixin(PictureInPictureRenderer.class)
abstract class PictureInPictureSkinsMixin {
	@Inject(method = "prepare", at = @At("HEAD"))
	private void flint$pictureStart(CallbackInfo callback, @Local(argsOnly = true) PictureInPictureRenderState state,
		@Local(argsOnly = true) int guiScale) {
		if (state instanceof GuiEntityRenderState entity) {
			Mc263Skins.guiPicture(guiScale * entity.scale());
		}
	}

	@Inject(method = "prepare", at = @At("RETURN"))
	private void flint$pictureEnd(CallbackInfo callback) {
		Mc263Skins.guiPicture(-1);
	}
}
