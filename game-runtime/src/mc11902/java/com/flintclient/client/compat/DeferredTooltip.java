package com.flintclient.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * A tooltip asked for while a screen draws, drawn once the screen has finished (1.20's deferred tooltip, which Minecraft
 * 1.19.2 lacks): mixin.feature.DeferredTooltipMixin calls {@link #draw} right after GameRenderer has the screen drawn. The
 * last one asked for in a frame wins, as on 1.20. Render thread only.
 */
public final class DeferredTooltip {
	private static @Nullable Font font;
	private static @Nullable List<FormattedCharSequence> lines;
	private static ClientTooltipPositioner positioner = DefaultTooltipPositioner.INSTANCE;
	private static int x;
	private static int y;

	private DeferredTooltip() {
	}

	public static void set(Font font, List<FormattedCharSequence> lines, ClientTooltipPositioner positioner, int x, int y) {
		DeferredTooltip.font = font;
		DeferredTooltip.lines = lines;
		DeferredTooltip.positioner = positioner;
		DeferredTooltip.x = x;
		DeferredTooltip.y = y;
	}

	/** Draws the tooltip asked for during this frame's screen, if any, and forgets it. */
	public static void draw(PoseStack pose) {
		List<FormattedCharSequence> pending = lines;
		Font pendingFont = font;
		lines = null;
		font = null;
		if (pending != null && pendingFont != null) {
			GuiGraphics.of(pose).renderTooltip(pendingFont, pending, positioner, x, y);
		}
	}
}
