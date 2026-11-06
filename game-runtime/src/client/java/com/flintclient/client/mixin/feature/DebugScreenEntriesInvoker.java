package com.flintclient.client.mixin.feature;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Saturation: adds its debug screen entry (food_stats) to Minecraft's list, as AppleSkin does through Fabric API's widener. */
@Mixin(DebugScreenEntries.class)
public interface DebugScreenEntriesInvoker {
	@Invoker("register")
	static Identifier flint$register(Identifier id, DebugScreenEntry entry) {
		throw new AssertionError();
	}
}
