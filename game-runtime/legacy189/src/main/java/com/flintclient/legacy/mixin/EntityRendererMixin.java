package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import com.flintclient.legacy.Marks;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.living.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Nametags: the plate's darkness (or none), a shadow under the name, and the Flint mark before a Flint player's name. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	private static final String TAG = "renderNameTag(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI)V";

	/** A Flint player's name gets the mark's room (Marks): the plate is measured with it. */
	@ModifyVariable(method = TAG, at = @At("HEAD"), argsOnly = true)
	private String flint$markName(String name, Entity entity, String sameName, double dx, double dy, double dz, int maxDistance) {
		return Flint.ready() && entity instanceof PlayerEntity ? Marks.mark(entity.getUuid(), name) : name;
	}

	@ModifyArg(method = TAG, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/vertex/BufferBuilder;color(FFFF)Lnet/minecraft/client/render/vertex/BufferBuilder;"), index = 3)
	private float flint$plate(float alpha) {
		return Flint.ready() ? Flint.modules().nametags.plateAlpha(alpha) : alpha;
	}

	/** The faint name seen through walls (drawn first): its mark as faint. */
	@Redirect(method = TAG, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/TextRenderer;draw(Ljava/lang/String;III)I", ordinal = 0))
	private int flint$seeThrough(TextRenderer font, String text, int x, int y, int color) {
		return Marks.draw(font, text, x, y, color, false);
	}

	/** The name drawn last (in front of everything, after the see-through pass), with its mark. */
	@Redirect(method = TAG, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/TextRenderer;draw(Ljava/lang/String;III)I", ordinal = 1))
	private int flint$name(TextRenderer font, String text, int x, int y, int color) {
		boolean shadow = Flint.ready() && Flint.modules().nametags.enabled() && Flint.modules().nametags.shadow.on();
		return Marks.draw(font, text, x, y, color, shadow);
	}
}
