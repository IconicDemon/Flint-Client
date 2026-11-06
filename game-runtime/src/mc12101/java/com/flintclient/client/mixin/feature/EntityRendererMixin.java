package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.state.EntityRenderState;
import com.flintclient.client.compat.state.RenderStates;
import com.flintclient.client.compat.state.SubmitNodeCollector;
import com.flintclient.client.control.platform.Mc263MoreMods;
import com.flintclient.client.control.platform.Mc263Nametags;
import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nametags as Flint shows them on Minecraft 1.21.1: Nick Hider's names, the Nametags mod's additions (size, opacity,
 * background, shadow, hurt tint), Totem Tracker's count, the Flint mark; and players' chat bubbles above them. 1.21.1 draws a
 * nametag in renderNameTag, in two parts (the see-through one with its background, then the solid text), straight into
 * the buffers.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;renderNameTag(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V"))
	private void flint$nameTag(EntityRenderer<?> self, Entity entity, Component name, PoseStack pose, MultiBufferSource buffers, int light,
		float partialTick, Operation<Void> original) {
		Component shown = Mc263Overlay.pingTag(entity, Mc263Nametags.badge(entity,
			Mc263MoreMods.totemTag(entity, Mc263Nametags.text(entity, Mc263Overlay.nameTag(entity, name)))));
		EntityRenderState state = entity instanceof LivingEntity ? RenderStates.of(entity) : RenderStates.fill(entity, partialTick, light, shown);
		Mc263Nametags.drawing(state);
		try {
			original.call(self, entity, shown, pose, buffers, light, partialTick);
		} finally {
			Mc263Nametags.drawing(null);
		}
	}

	/** Nametags' size. */
	@WrapOperation(method = "renderNameTag", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
	private void flint$size(PoseStack pose, float x, float y, float z, Operation<Void> original) {
		float k = Mc263Nametags.size();
		original.call(pose, x * k, y * k, z * k);
	}

	/** The first part: see-through walls (with its background), or the whole tag of a sneaking entity. */
	@WrapOperation(method = "renderNameTag", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I"))
	private int flint$firstPart(Font font, Component text, float x, float y, int color, boolean shadow, Matrix4f pose, MultiBufferSource buffers,
		Font.DisplayMode mode, int background, int light, Operation<Integer> original) {
		return flint$part(font, text, x, y, color, shadow, pose, buffers, mode, background, light, original);
	}

	/** The solid text over a see-through tag. */
	@WrapOperation(method = "renderNameTag", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I"))
	private int flint$solidPart(Font font, Component text, float x, float y, int color, boolean shadow, Matrix4f pose, MultiBufferSource buffers,
		Font.DisplayMode mode, int background, int light, Operation<Integer> original) {
		return flint$part(font, text, x, y, color, shadow, pose, buffers, mode, background, light, original);
	}

	private static int flint$part(Font font, Component text, float x, float y, int color, boolean shadow, Matrix4f pose, MultiBufferSource buffers,
		Font.DisplayMode mode, int background, int light, Operation<Integer> original) {
		boolean shaded = shadow || Mc263Nametags.shadow(mode);
		Mc263Nametags.Look look = Mc263Nametags.look(color, background, mode);
		if (look == null) {
			return original.call(font, text, x, y, color, shaded, pose, buffers, mode, background, light);
		}
		if (look.skip) {
			return 0;
		}
		// 1.21.1 has no standalone backgrounds: a shaped one is drawn as Minecraft's plain background (its rows say so).
		return original.call(font, text, x, y, look.text, shaded, pose, buffers, mode, look.background != 0 ? look.background : look.fill, light);
	}

	/** Players' chat bubbles, above their nametag (or where it would be). */
	@Inject(method = "render", at = @At("TAIL"))
	private void flint$bubbles(Entity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo callback) {
		EntityRenderState state = RenderStates.peek(entity);
		if (state != null) {
			Mc263Nametags.bubbles(state, pose, new SubmitNodeCollector(buffers), RenderStates.camera());
		}
	}
}
