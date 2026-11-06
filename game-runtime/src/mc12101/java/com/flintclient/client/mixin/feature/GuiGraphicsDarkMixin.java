package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263DarkMode;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dark Mode on Minecraft 1.21.1: Minecraft's GUI sprites and textures drawn in its shade, and its dark grey menu text
 * (inventory titles and labels) in light grey so it reads on them. 1.21.1 has no colour on a sprite or texture draw: the
 * shade goes on the shader colour for the length of the draw (on top of any tint already set) and is put back after.
 */
@Mixin(GuiGraphics.class)
abstract class GuiGraphicsDarkMixin {
	@Unique
	private final float[] flint$saved = new float[4];
	@Unique
	private int flint$depth;
	@Unique
	private boolean flint$shaded;

	@Unique
	private void flint$shade(int argb) {
		if (flint$depth++ > 0 || argb == -1) {
			return;
		}
		float[] now = RenderSystem.getShaderColor();
		System.arraycopy(now, 0, flint$saved, 0, 4);
		RenderSystem.setShaderColor(now[0] * (argb >> 16 & 0xFF) / 255.0F, now[1] * (argb >> 8 & 0xFF) / 255.0F, now[2] * (argb & 0xFF) / 255.0F,
			now[3] * (argb >>> 24) / 255.0F);
		flint$shaded = true;
	}

	@Unique
	private void flint$unshade() {
		if (--flint$depth > 0) {
			return;
		}
		flint$depth = 0;
		if (flint$shaded) {
			flint$shaded = false;
			RenderSystem.setShaderColor(flint$saved[0], flint$saved[1], flint$saved[2], flint$saved[3]);
		}
	}

	@Inject(method = "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIII)V", at = @At("HEAD"))
	private void flint$sprite(ResourceLocation sprite, int x, int y, int z, int w, int h, CallbackInfo callback) {
		flint$shade(Mc263DarkMode.sprite(sprite, -1));
	}

	@Inject(method = "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIII)V", at = @At("RETURN"))
	private void flint$spriteEnd(ResourceLocation sprite, int x, int y, int z, int w, int h, CallbackInfo callback) {
		flint$unshade();
	}

	@Inject(method = "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIIIIIII)V", at = @At("HEAD"))
	private void flint$spritePart(ResourceLocation sprite, int tw, int th, int u, int v, int x, int y, int z, int w, int h, CallbackInfo callback) {
		flint$shade(Mc263DarkMode.sprite(sprite, -1));
	}

	@Inject(method = "blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIIIIIII)V", at = @At("RETURN"))
	private void flint$spritePartEnd(ResourceLocation sprite, int tw, int th, int u, int v, int x, int y, int z, int w, int h,
		CallbackInfo callback) {
		flint$unshade();
	}

	@Inject(method = "blit(Lnet/minecraft/resources/ResourceLocation;IIIIIIIFFII)V", at = @At("HEAD"))
	private void flint$texture(ResourceLocation texture, int x0, int x1, int y0, int y1, int z, int w, int h, float u, float v, int tw, int th,
		CallbackInfo callback) {
		flint$shade(Mc263DarkMode.texture(texture, -1));
	}

	@Inject(method = "blit(Lnet/minecraft/resources/ResourceLocation;IIIIIIIFFII)V", at = @At("RETURN"))
	private void flint$textureEnd(ResourceLocation texture, int x0, int x1, int y0, int y1, int z, int w, int h, float u, float v, int tw, int th,
		CallbackInfo callback) {
		flint$unshade();
	}

	@ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I", at = @At("HEAD"), argsOnly = true, ordinal = 2)
	private int flint$string(int color) {
		return Mc263DarkMode.label(color);
	}

	@ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I", at = @At("HEAD"),
		argsOnly = true, ordinal = 2)
	private int flint$sequence(int color) {
		return Mc263DarkMode.label(color);
	}

	@ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I", at = @At("HEAD"),
		argsOnly = true, ordinal = 2)
	private int flint$component(int color) {
		return Mc263DarkMode.label(color);
	}
}
