package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import com.flintclient.legacy.OwnNametag;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.living.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Hit Color: the hurt tint's colour and strength (Minecraft puts red, green, blue and strength into its tint buffer, in that
 * order, before any other colour). Nametags: your own nametag in third person, by Minecraft's own nametag rules (OwnNametag).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	private static final String SETUP = "setupOverlayColor(Lnet/minecraft/entity/living/LivingEntity;FZ)Z";
	private static final String PUT = "Ljava/nio/FloatBuffer;put(F)Ljava/nio/FloatBuffer;";

	@ModifyArg(method = SETUP, at = @At(value = "INVOKE", target = PUT, ordinal = 0, remap = false))
	private float flint$hurtRed(float v) {
		return Flint.ready() ? Flint.modules().hitColor.part(0, v) : v;
	}

	@ModifyArg(method = SETUP, at = @At(value = "INVOKE", target = PUT, ordinal = 1, remap = false))
	private float flint$hurtGreen(float v) {
		return Flint.ready() ? Flint.modules().hitColor.part(1, v) : v;
	}

	@ModifyArg(method = SETUP, at = @At(value = "INVOKE", target = PUT, ordinal = 2, remap = false))
	private float flint$hurtBlue(float v) {
		return Flint.ready() ? Flint.modules().hitColor.part(2, v) : v;
	}

	@ModifyArg(method = SETUP, at = @At(value = "INVOKE", target = PUT, ordinal = 3, remap = false))
	private float flint$hurtStrength(float v) {
		return Flint.ready() ? Flint.modules().hitColor.part(3, v) : v;
	}

	@ModifyReturnValue(method = "shouldRenderNameTag(Lnet/minecraft/entity/living/LivingEntity;)Z", at = @At("RETURN"))
	private boolean flint$ownNametag(boolean shown, LivingEntity entity) {
		return OwnNametag.shown(entity, shown);
	}
}
