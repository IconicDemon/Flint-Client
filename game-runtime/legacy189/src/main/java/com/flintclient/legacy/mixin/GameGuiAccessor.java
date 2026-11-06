package com.flintclient.legacy.mixin;

import net.minecraft.client.gui.GameGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How long Minecraft's action bar message still shows (Day Counter makes way for it, as on the other lines). */
@Mixin(GameGui.class)
public interface GameGuiAccessor {
	@Accessor("overlayMessageCooldown")
	int flint$overlayMessageCooldown();
}
