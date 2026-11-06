/*
 * Adapted from Custom Fog by Setadokalo (MIT License; text in META-INF/licenses/MIT-CustomFog.txt). Changed for Flint: its
 * configs are Flint settings (worked out in control.feature.FogPlan), and its fog is written into Minecraft 26.x's fog data
 * (environmental and render-distance fog) instead of the 1.21 shader's single start and end.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.control.feature.FogPlan;
import com.flintclient.client.control.model.ModuleState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;

/**
 * Fog Customizer's fog on this Minecraft: the fog Minecraft just computed goes through {@link FogPlan}, which decides it the
 * same way on every line, and comes back. This class only reads the game: where the camera is, Blindness, the dimension and
 * the render distance.
 */
final class Mc263Fog {
	/** The plan's copy of the fog, reused each time (render thread only). */
	private static final FogPlan.Distances DISTANCES = new FogPlan.Distances();

	private Mc263Fog() {
	}

	/** Adjusts {@code fog} (the camera's {@code type} of fog); {@code skyAndClouds}: see {@link FogPlan#adjust}. */
	static void apply(FogData fog, FogType type, ModuleState s, boolean skyAndClouds) {
		Minecraft mc = Minecraft.getInstance();
		Entity camera = mc.getCameraEntity();
		boolean blind = camera instanceof LivingEntity living && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
		FogPlan.Medium medium = medium(type);
		FogPlan.Distances d = DISTANCES;
		d.environmentalStart = fog.environmentalStart;
		d.environmentalEnd = fog.environmentalEnd;
		d.renderDistanceStart = fog.renderDistanceStart;
		d.renderDistanceEnd = fog.renderDistanceEnd;
		d.skyEnd = fog.skyEnd;
		d.cloudEnd = fog.cloudEnd;
		FogPlan.adjust(d, medium, blind, medium == FogPlan.Medium.AIR ? Mc263Features.dimension() : null,
			FogPlan.view(mc.options.getEffectiveRenderDistance()), s, skyAndClouds);
		fog.environmentalStart = d.environmentalStart;
		fog.environmentalEnd = d.environmentalEnd;
		fog.renderDistanceStart = d.renderDistanceStart;
		fog.renderDistanceEnd = d.renderDistanceEnd;
		fog.skyEnd = d.skyEnd;
		fog.cloudEnd = d.cloudEnd;
	}

	/** Where the camera is, by the fog type Minecraft gives it (NONE: in the open). */
	static FogPlan.Medium medium(FogType type) {
		if (type == FogType.NONE) {
			return FogPlan.Medium.AIR;
		}
		if (type == FogType.WATER) {
			return FogPlan.Medium.WATER;
		}
		if (type == FogType.LAVA) {
			return FogPlan.Medium.LAVA;
		}
		return type == FogType.POWDER_SNOW ? FogPlan.Medium.POWDER_SNOW : FogPlan.Medium.OTHER;
	}
}
