package com.flintclient.client.graphics.platform;

import com.flintclient.client.graphics.GraphicsPlatform;
import com.mojang.blaze3d.platform.GlUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 1.21.1, which has no GPU device object: the OpenGL context answers directly (GlUtil), once it exists and only on
 * the render thread that owns it.
 */
public final class Mc263GraphicsPlatform implements GraphicsPlatform {
	@Override
	public @Nullable Device device() {
		if (!RenderSystem.isOnRenderThread()) {
			return null;
		}
		try {
			String renderer = GlUtil.getRenderer();
			if (renderer == null) {
				return null;
			}
			return new Device("OpenGL", text(renderer), text(GlUtil.getVendor()), text(GlUtil.getOpenGLVersion()), RenderSystem.maxSupportedTextureSize());
		} catch (RuntimeException e) {
			// No context yet.
			return null;
		}
	}

	// The OpenGL strings come straight from the driver (glGetString), which may return null.
	private static String text(@Nullable String value) {
		return value == null ? "" : value.strip();
	}
}
