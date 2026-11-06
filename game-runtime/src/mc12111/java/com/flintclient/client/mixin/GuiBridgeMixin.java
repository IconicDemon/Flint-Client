package com.flintclient.client.mixin;

import com.flintclient.client.compat.GuiBridge;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.21.11 (as 26.1): its {@code Gui} (the HUD) answers the screen and HUD methods 26.2 split between Gui and Hud ({@link GuiBridge}). */
@Mixin(Gui.class)
abstract class GuiBridgeMixin implements GuiBridge {
}
