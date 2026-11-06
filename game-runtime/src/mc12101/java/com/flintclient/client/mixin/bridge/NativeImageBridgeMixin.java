package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Minecraft 1.21.1: NativeImage's 0xAARRGGBB pixel access and its pixels' address ({@link Bridges.Image}). */
@Mixin(NativeImage.class)
abstract class NativeImageBridgeMixin implements Bridges.Image {
	@Shadow
	private long pixels;

	@Override
	public long getPointer() {
		return pixels;
	}
}
