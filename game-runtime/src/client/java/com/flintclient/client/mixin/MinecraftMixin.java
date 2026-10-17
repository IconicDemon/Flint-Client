package com.flintclient.client.mixin;

import com.flintclient.client.render.FontPreload;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Hears the GUI scale {@code resizeGui} settles on (window resizes, the GUI scale option, fullscreen),
 * before the open screen is laid out again at it, so Flint's fonts for a new scale can start loading on
 * a worker ({@code fontPreload}). Passing the argument through unchanged, rather than injecting a
 * callback, allocates nothing.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@ModifyArg(method = "resizeGui", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;setGuiScale(I)V"))
	private int flint$guiScaleChosen(int guiScale) {
		FontPreload.guiScaleChosen(guiScale);
		return guiScale;
	}
}
