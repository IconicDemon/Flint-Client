package com.flintclient.client.control.platform;

import net.minecraft.client.Minecraft;

/** 1.21.x has no Exclusive Fullscreen switch to keep in step: Flint makes the borderless window itself (FullscreenModeMixin). */
public final class FullscreenSync {
	private FullscreenSync() {
	}

	static void tick(Minecraft mc) {
	}
}
