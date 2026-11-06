package com.flintclient.legacy.mixin;

import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A button's width, which 1.8.9 keeps to itself (the title screen's Realms button makes room for Daily Rewards). */
@Mixin(ButtonWidget.class)
public interface ButtonWidgetAccessor {
	@Accessor("width")
	int flint$width();

	@Accessor("width")
	void flint$setWidth(int width);
}
