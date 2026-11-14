package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.Mc263Cosmetics;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player drawn in a screen from a render state made for it (the wardrobe's preview player, which has no entity to extract
 * from) wears what the wardrobe says while it draws it ({@code control/feature/CosmeticPreview#wearing}).
 */
@Mixin(GuiGraphics.class)
abstract class GuiEntityCosmeticsMixin {
	@Inject(method = "submitEntityRenderState", at = @At("HEAD"))
	private void flint$previewCosmetics(EntityRenderState state, float scale, Vector3f translation, Quaternionf rotation, Quaternionf camera,
		int x0, int y0, int x1, int y1, CallbackInfo callback) {
		if (state instanceof AvatarRenderState avatar) {
			Mc263Cosmetics.preview(avatar);
		}
	}
}
