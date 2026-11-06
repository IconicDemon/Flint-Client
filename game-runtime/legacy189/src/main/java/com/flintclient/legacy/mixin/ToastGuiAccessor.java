package com.flintclient.legacy.mixin;

import net.minecraft.client.gui.ToastGui;
import net.minecraft.stat.achievement.AchievementStat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Minecraft 1.8.9's achievement pop-up at the top right (Flint Friends' notices make room for it: FriendNotices189). */
@Mixin(ToastGui.class)
public interface ToastGuiAccessor {
	@Accessor("achievement")
	AchievementStat flint$achievement();

	@Accessor("startTime")
	long flint$startTime();

	@Accessor("tutorial")
	boolean flint$tutorial();
}
