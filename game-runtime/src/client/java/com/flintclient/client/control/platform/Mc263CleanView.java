/*
 * Adapted from CleanView (MIT License; full text in META-INF/licenses/MIT-CleanView.txt). Changed for Flint: its settings
 * come from Flint's Particle Changer module, and the opacity reads the particle's position without allocating.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.ParticleChanger;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Particle Changer's first-person particle visibility, as CleanView does it: in first person, a particle about to be created
 * in the filter area (your face, your body, or a number of blocks ahead of you) is left out with a chance set per kind
 * (eating and item crumbs; potion swirls within 2 blocks), and every particle near your eyes can be drawn see-through.
 * With the module off nothing is filtered: hiding your own potion swirls is Animations' "Disable Camera Potion Particles"
 * alone, so that switch (and the mod) decide it. Fault-isolated; nothing allocated per particle.
 */
public final class Mc263CleanView {
	private static final HookGuard GUARD = new HookGuard("particle visibility",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int PARTICLES = Mods.index(ParticleChanger.ID);

	private Mc263CleanView() {
	}

	/** {@code ParticleEngine.createParticle}: false keeps the particle from being created. */
	public static boolean create(ParticleOptions options, double x, double y, double z) {
		if (!GUARD.on()) {
			return true;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.gameRenderer == null || !mc.options.getCameraType().isFirstPerson()) {
				return true;
			}
			if (!Mods.on(PARTICLES)) {
				return true;
			}
			ModuleState s = Mods.state(PARTICLES);
			if (!withinFilterDistance(mc, s.integer(ParticleChanger.FILTER_DISTANCE), x, y, z)) {
				return true;
			}
			ParticleType<?> type = options.getType();
			if (type == ParticleTypes.ITEM && filtered(s.integer(ParticleChanger.EATING))) {
				return false;
			}
			if (type != ParticleTypes.ENTITY_EFFECT && type != ParticleTypes.EFFECT && type != ParticleTypes.INSTANT_EFFECT) {
				return true;
			}
			Entity camera = mc.getCameraEntity();
			return camera == null || camera.distanceToSqr(x, y, z) >= 4.0 || !filtered(s.integer(ParticleChanger.EFFECTS));
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return true;
		}
	}

	/** A quad particle's color as it's drawn at camera-relative {@code x, y, z}: see-through near your eyes when asked. */
	public static int color(float x, float y, float z, int color) {
		if (!GUARD.on() || !Mods.on(PARTICLES)) {
			return color;
		}
		ModuleState s = Mods.state(PARTICLES);
		int percent = s.integer(ParticleChanger.OPACITY);
		if (!s.bool(ParticleChanger.OPACITY_ON) || percent >= 100) {
			return color;
		}
		Minecraft mc = Minecraft.getInstance();
		int distance = s.integer(ParticleChanger.OPACITY_DISTANCE);
		if (mc.player == null || !mc.options.getCameraType().isFirstPerson() || distance <= 0
			|| x * x + y * y + z * z > (double) distance * distance) {
			return color;
		}
		return Math.round((color >>> 24) * percent / 100.0F) << 24 | color & 0xFFFFFF;
	}

	/** Whether a particle of a kind shown {@code visibility}% of the time is left out this time. */
	private static boolean filtered(int visibility) {
		double keep = visibility >= 100 ? 1 : visibility <= 0 ? 0 : visibility <= 50 ? (visibility / 100.0) * (visibility / 100.0) : visibility / 100.0;
		return keep < 1 && (keep <= 0 || Math.random() >= keep);
	}

	private static boolean withinFilterDistance(Minecraft mc, int distance, double x, double y, double z) {
		Entity camera = mc.getCameraEntity();
		if (camera == null) {
			return false;
		}
		Vec3 view = camera.getViewVector(1.0F);
		double viewLength = Math.sqrt(view.x * view.x + view.z * view.z);
		if (viewLength < 1.0E-4) {
			return distance >= 1 && camera.distanceToSqr(x, y, z) < 1.0;
		}
		if (distance <= 0) {
			return false;
		}
		double lookX = view.x / viewLength;
		double lookZ = view.z / viewLength;
		double deltaX = x - camera.getX();
		double deltaZ = z - camera.getZ();
		double forward = deltaX * lookX + deltaZ * lookZ;
		double side = deltaX * lookZ - deltaZ * lookX;
		if (Math.abs(side) > 0.85) {
			return false;
		}
		double feetY = camera.getY();
		boolean inBodyBlock = forward >= -0.75 && forward <= 0.85;
		double eyeOffset = y - camera.getEyeY();
		boolean inFace = inBodyBlock && eyeOffset >= -0.9 && eyeOffset <= 0.75;
		if (distance == 1) {
			return inFace;
		}
		boolean inBody = inBodyBlock && y >= feetY - 1.1 && y <= feetY + 2.1;
		if (distance == 2) {
			return inFace || inBody;
		}
		double reach = 0.85 + distance - 2.0;
		boolean ahead = forward > 0.85 && forward <= reach && y >= feetY - 1.1 && y <= feetY + 2.1;
		return inFace || inBody || ahead;
	}
}
