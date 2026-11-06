package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.21.1: Style takes a font as a FontDescription ({@link Bridges.StyleFont}). */
@Mixin(Style.class)
abstract class StyleFontBridgeMixin implements Bridges.StyleFont {
}
