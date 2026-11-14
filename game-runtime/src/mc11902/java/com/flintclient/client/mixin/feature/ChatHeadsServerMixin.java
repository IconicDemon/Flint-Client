package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ChatHeads;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chat Heads: a server whose resource pack carries chat_heads:disable gets no heads (Chat Heads' own opt-out, obeyed). Minecraft
 * 1.19.2 has one server pack at a time (no DownloadedPackSource): it's read as ClientPackSource takes it on (a server's pack, or a
 * world's own resources.zip).
 */
@Mixin(ClientPackSource.class)
abstract class ChatHeadsServerMixin {
	@Shadow
	private @Nullable Pack serverPack;

	@Inject(method = "setServerPack", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER,
		target = "Lnet/minecraft/client/resources/ClientPackSource;serverPack:Lnet/minecraft/server/packs/repository/Pack;"))
	private void flint$serverOptOut(File file, PackSource source, CallbackInfoReturnable<CompletableFuture<Void>> callback) {
		Pack pack = serverPack;
		if (pack == null) {
			return;
		}
		try (PackResources resources = pack.open()) {
			if (resources.hasResource(PackType.CLIENT_RESOURCES, Mc263ChatHeads.DISABLE_RESOURCE)) {
				Mc263ChatHeads.disabledByServer();
			}
		} catch (RuntimeException | LinkageError e) {
			// A pack that can't be read can't ask either.
		}
	}
}
