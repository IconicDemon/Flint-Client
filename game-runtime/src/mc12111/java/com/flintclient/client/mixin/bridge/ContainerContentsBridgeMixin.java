package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.21.11: ItemContainerContents answers the 26.x methods in {@link Bridges.ContainerContents}. */
@Mixin(ItemContainerContents.class)
abstract class ContainerContentsBridgeMixin implements Bridges.ContainerContents {
}
