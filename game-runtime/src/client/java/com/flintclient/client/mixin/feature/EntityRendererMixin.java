package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Nick Hider on nametags: your name (and, when asked, other players') replaced on your screen only. */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
	@ModifyReturnValue(method = "getNameTag", at = @At("RETURN"))
	private @Nullable Component flint$nameTag(@Nullable Component name, Entity entity) {
		return name == null ? null : com.flintclient.client.control.platform.Mc263Nametags.text(entity, Mc263Overlay.nameTag(entity, name));
	}
}
