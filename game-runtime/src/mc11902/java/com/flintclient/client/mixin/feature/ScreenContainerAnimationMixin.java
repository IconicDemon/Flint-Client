package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.gui.ModelView;
import com.flintclient.client.control.platform.Mc263Inventory;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory's open and close animations (Minecraft 1.19.2): the container moves after the background, around everything
 * else the screen draws. A 1.19.2 container screen draws its background itself, first thing in its render; the container
 * moves on the model-view pose (compat.gui.ModelView), which its items are drawn through, and moves back once the screen is
 * drawn (mixin.feature.ScreenContainerFrameMixin, which also hands it the pointer on the moving container).
 */
@Mixin(Screen.class)
abstract class ScreenContainerAnimationMixin {
	@Inject(method = "renderBackground(Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("RETURN"))
	private void flint$containerMoves(PoseStack pose, CallbackInfo callback) {
		if ((Object) this instanceof AbstractContainerScreen<?>) {
			Mc263Inventory.animationBegin(ModelView.graphics(), (Screen) (Object) this);
			if (Mc263Inventory.moving()) {
				ModelView.apply();
			}
		}
	}
}
