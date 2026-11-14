package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DataFixerBuilder;
import java.util.concurrent.Executor;
import net.minecraft.util.datafix.DataFixers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Instant Swap's standby game on 1.19 ({@link InstantSwap#standby}): its data fixers are built unoptimised. 1.19 optimises
 * all of them at start-up on every bootstrap thread (about 28 s of CPU on this PC, logged as "Datafixer optimizations took"),
 * so a standby started at the Library's Install was still doing it when it joined the world, and its world took twice as long
 * to load (the uninstall swap's 12 s). The standby only reads the world the player just saved (the current version, nothing to
 * upgrade), so it never needs them; 1.20.5 made Minecraft's own fixers lazy the same way. Any other game is untouched.
 */
@Mixin(DataFixers.class)
abstract class SwapDataFixerMixin {
	@Redirect(method = "createFixerUpper", require = 0, at = @At(value = "INVOKE", remap = false,
		target = "Lcom/mojang/datafixers/DataFixerBuilder;buildOptimized(Ljava/util/concurrent/Executor;)Lcom/mojang/datafixers/DataFixer;"))
	private static DataFixer flint$unoptimisedForStandby(DataFixerBuilder builder, Executor executor) {
		return InstantSwap.standby() ? builder.buildUnoptimized() : builder.buildOptimized(executor);
	}
}
