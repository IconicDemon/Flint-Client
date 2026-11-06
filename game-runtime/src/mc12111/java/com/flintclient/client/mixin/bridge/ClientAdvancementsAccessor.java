package com.flintclient.client.mixin.bridge;

import java.util.Map;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.multiplayer.ClientAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Minecraft 1.21.11: ClientAdvancements' progress map (26.x reads it by name; 1.21.11's names are obfuscated). */
@Mixin(ClientAdvancements.class)
public interface ClientAdvancementsAccessor {
	@Accessor("progress")
	Map<AdvancementHolder, AdvancementProgress> flint$progress();
}
