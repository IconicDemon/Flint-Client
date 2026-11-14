package com.flintclient.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;

/**
 * How a screen's frame is drawn on Minecraft 1.19.2, the way 1.20.2 and later draw it: the background first (Screen.render no
 * longer draws it there), then the screen, then the tooltip it asked for ({@link DeferredTooltip}). For Flint's screens
 * only (their renderBackground is the 1.20.2 form, which the rules turn into 1.19.2's renderBackground(PoseStack)); 1.19.2's
 * own screens draw their background themselves. mixin.feature.ScreenBackgroundMixin runs this where GameRenderer draws the
 * screen. Render thread only.
 */
public final class ScreenFrame {
	private static int mouseX;
	private static int mouseY;
	private static float partialTick;

	private ScreenFrame() {
	}

	public static boolean flintScreen(Screen screen) {
		return screen.getClass().getName().startsWith("com.flintclient.");
	}

	/** Before the screen draws: a Flint screen's background, with the frame's pointer and partial tick at hand. */
	public static void begin(Screen screen, PoseStack pose, int x, int y, float tick) {
		mouseX = x;
		mouseY = y;
		partialTick = tick;
		if (flintScreen(screen)) {
			screen.renderBackground(pose);
		}
	}

	/** After the screen drew: the tooltip it asked for. */
	public static void end(PoseStack pose) {
		DeferredTooltip.draw(pose);
	}

	/** A Flint screen's background drawn inside another's frame, with the pointer and partial tick given (1.20.2's renderBackground arguments). */
	public static void background(Screen screen, GuiGraphics graphics, int x, int y, float tick) {
		int beforeX = mouseX;
		int beforeY = mouseY;
		float beforeTick = partialTick;
		mouseX = x;
		mouseY = y;
		partialTick = tick;
		try {
			screen.renderBackground(graphics.pose());
		} finally {
			mouseX = beforeX;
			mouseY = beforeY;
			partialTick = beforeTick;
		}
	}

	public static int mouseX() {
		return mouseX;
	}

	public static int mouseY() {
		return mouseY;
	}

	public static float partialTick() {
		return partialTick;
	}
}
