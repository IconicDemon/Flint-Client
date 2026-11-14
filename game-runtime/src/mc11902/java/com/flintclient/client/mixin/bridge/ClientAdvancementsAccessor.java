package com.flintclient.client.mixin.bridge;

import java.util.Map;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.multiplayer.ClientAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Minecraft 1.19.2: ClientAdvancements' progress map, which it keeps private. 1.19.2 keys it by the advancement itself
 * (1.20.2 added AdvancementHolder, the id and advancement together, which later versions key it by).
 */
@Mixin(ClientAdvancements.class)
public interface ClientAdvancementsAccessor {
	@Accessor("progress")
	Map<Advancement, AdvancementProgress> flint$progress();
}
