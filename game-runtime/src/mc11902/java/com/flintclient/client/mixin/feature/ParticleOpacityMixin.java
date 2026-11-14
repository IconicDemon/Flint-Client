package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263CleanView;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.math.Vector3f;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Particle Changer's particle opacity (CleanView) on Minecraft 1.19.2: a quad particle near your eyes in first person is
 * drawn see-through. 1.19.2 builds the whole quad in render, its four corners (camera-relative, in that order) in one array
 * before it writes them, so each corner's alpha (its color call's fourth number) is lowered there; nothing is allocated per
 * particle.
 */
@Mixin(SingleQuadParticle.class)
abstract class ParticleOpacityMixin {
	@Unique
	private static final String RENDER = "render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V";
	@Unique
	private static final String COLOR = "Lcom/mojang/blaze3d/vertex/VertexConsumer;color(FFFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;";

	@ModifyArg(method = RENDER, index = 3, at = @At(value = "INVOKE", ordinal = 0, target = COLOR))
	private float flint$opacity0(float alpha, @Local Vector3f[] corners) {
		return flint$opacity(alpha, corners[0]);
	}

	@ModifyArg(method = RENDER, index = 3, at = @At(value = "INVOKE", ordinal = 1, target = COLOR))
	private float flint$opacity1(float alpha, @Local Vector3f[] corners) {
		return flint$opacity(alpha, corners[1]);
	}

	@ModifyArg(method = RENDER, index = 3, at = @At(value = "INVOKE", ordinal = 2, target = COLOR))
	private float flint$opacity2(float alpha, @Local Vector3f[] corners) {
		return flint$opacity(alpha, corners[2]);
	}

	@ModifyArg(method = RENDER, index = 3, at = @At(value = "INVOKE", ordinal = 3, target = COLOR))
	private float flint$opacity3(float alpha, @Local Vector3f[] corners) {
		return flint$opacity(alpha, corners[3]);
	}

	@Unique
	private static float flint$opacity(float alpha, Vector3f corner) {
		int color = Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24 | 0xFFFFFF;
		int shown = Mc263CleanView.color(corner.x(), corner.y(), corner.z(), color);
		return shown == color ? alpha : (shown >>> 24) / 255.0F;
	}
}
