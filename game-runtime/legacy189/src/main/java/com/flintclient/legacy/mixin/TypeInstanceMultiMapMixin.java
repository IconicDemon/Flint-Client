package com.flintclient.legacy.mixin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.TypeInstanceMultiMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Missing chunks in single player (Minecraft 1.8.9's own bug): every chunk section keeps its entities in a
 * {@code TypeInstanceMultiMap}, and all of them share one static set of the entity classes looked up so far, a plain
 * {@code HashSet}. The integrated server's thread adds to it (a new class looked up while it ticks or makes chunks) while the
 * client's thread walks it making the chunks a server packet brings; the walk then throws ConcurrentModificationException,
 * Minecraft logs "Error executing task" and drops the rest of that packet, and those chunks never come: holes in the world
 * until they are sent again. A set that two threads can share takes its place: the walk sees every class known when it
 * starts (a class added meanwhile is looked up when it is first asked for, as it always was).
 */
@Mixin(TypeInstanceMultiMap.class)
public abstract class TypeInstanceMultiMapMixin {
	@Shadow
	@Final
	@Mutable
	private static Set<Class<?>> ALL_TYPES;

	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void flint$sharedTypes(CallbackInfo ci) {
		Set<Class<?>> types = ConcurrentHashMap.newKeySet();
		types.addAll(ALL_TYPES);
		ALL_TYPES = types;
	}
}
