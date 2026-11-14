package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.gui.DarkGui;
import com.mojang.math.Matrix4f;
import net.minecraft.client.gui.GuiComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dark Mode on Minecraft 1.19.2: Minecraft's GUI pictures drawn in its shade. 1.19.2 has no GuiGraphics: its GUI blits
 * (GuiComponent's, from buttons, containers and the HUD alike, by texture region or atlas sprite) all end in the one
 * innerBlit that draws the quad, with the texture bound beforehand; what that texture and region stand for decides the
 * shade (compat.gui.DarkGui). Its dark grey menu text is FontDarkMixin's.
 */
@Mixin(GuiComponent.class)
abstract class GuiGraphicsDarkMixin {
	@Inject(method = "innerBlit(Lcom/mojang/math/Matrix4f;IIIIIFFFF)V", at = @At("HEAD"))
	private static void flint$shade(Matrix4f matrix, int x0, int x1, int y0, int y1, int z, float u0, float u1, float v0, float v1,
		CallbackInfo callback) {
		DarkGui.begin(z, u0, v0);
	}

	@Inject(method = "innerBlit(Lcom/mojang/math/Matrix4f;IIIIIFFFF)V", at = @At("RETURN"))
	private static void flint$unshade(Matrix4f matrix, int x0, int x1, int y0, int y1, int z, float u0, float u1, float v0, float v1,
		CallbackInfo callback) {
		DarkGui.end();
	}
}
