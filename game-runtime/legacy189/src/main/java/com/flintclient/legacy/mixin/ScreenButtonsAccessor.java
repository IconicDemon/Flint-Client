package com.flintclient.legacy.mixin;

import java.util.List;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * A 1.8.9 screen's buttons and its click handling, for Flint's main menu: the buttons other mods put on Minecraft's title screen
 * (found on one laid out out of sight, ui/screen/ExternalButtons) are pressed through the screen's own {@code buttonClicked}, which
 * is where those mods answer their buttons.
 */
@Mixin(Screen.class)
public interface ScreenButtonsAccessor {
	@Accessor("buttons")
	List<ButtonWidget> flint$buttons();

	@Invoker("buttonClicked")
	void flint$buttonClicked(ButtonWidget button);
}
