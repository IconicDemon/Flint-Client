package com.flintclient.qa.mixin;

import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Minecraft 1.19.2: nothing to change on the player itself. Its skin, cape, elytra and model are read from its player info,
 * where PlayerInfoSkinMixin writes the tour's look ({@link com.flintclient.qa.QaSkin}) for Minecraft and Flint alike; this
 * class stays so the shared mixin config names the same classes on every line.
 */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
}
