package com.flintclient.client.compat;

import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * The tooltip style of the item tooltip being drawn (1.21.2's {@code TOOLTIP_STYLE}, which 1.21.1 lacks): Glint's own style,
 * set while an item's tooltip is drawn (GlintTooltipMixin, GlintScreenTooltipMixin) and read where its background is
 * (GlintBackgroundMixin). 1.21.1 draws a tooltip at once, so it lasts one call. Render thread only.
 */
public final class TooltipStyle {
	private static @Nullable ResourceLocation current;

	private TooltipStyle() {
	}

	public static @Nullable ResourceLocation current() {
		return current;
	}

	/** Sets the style for the tooltip about to be drawn; returns the one before, for {@link #restore}. */
	public static @Nullable ResourceLocation begin(@Nullable ResourceLocation style) {
		ResourceLocation before = current;
		current = style;
		return before;
	}

	public static void restore(@Nullable ResourceLocation before) {
		current = before;
	}
}
