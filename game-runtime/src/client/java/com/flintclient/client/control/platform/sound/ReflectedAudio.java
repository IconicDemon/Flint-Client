package com.flintclient.client.control.platform.sound;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where a sound seems to come from: the directions in which its rays (and their bounces) reach you through open air,
 * weighted by how far they travelled. Sound thread only.
 *
 * <p>Adapted from Sound Physics Remastered's {@code ReflectedAudio} (GPL-3.0; see
 * META-INF/licenses/GPL-3.0-SoundPhysicsRemastered.txt).
 */
final class ReflectedAudio {
	private final List<Vec3> directions = new ArrayList<>();
	private final List<Double> lengths = new ArrayList<>();
	private @Nullable Vec3 direct;
	private final double occlusion;
	private final @Nullable Identifier sound;
	private final SoundPhysicsParams params;
	private int sharedAirspaces;

	ReflectedAudio(double occlusion, @Nullable Identifier sound, SoundPhysicsParams params) {
		this.occlusion = occlusion;
		this.sound = sound;
		this.params = params;
	}

	boolean shouldEvaluateDirection() {
		return params.directionEvaluation && (occlusion > 0 || !params.redirectNonOccluded) && !SoundPhysics.isVoiceChat(sound);
	}

	int sharedAirspaces() {
		return sharedAirspaces;
	}

	void addDirectAirspace(Vec3 vector) {
		direct = vector;
	}

	void addSharedAirspace(Vec3 vector, double totalRayDistance) {
		double length = totalRayDistance + vector.length();
		if (length > 0 && !(length > params.maxDistance / params.attenuationFactor)) {
			sharedAirspaces++;
			if (shouldEvaluateDirection()) {
				directions.add(vector);
				lengths.add(length);
			}
		}
	}

	/** Where to move the sound to, or null to leave it. */
	@Nullable Vec3 evaluateSoundPosition(Vec3 soundPos, Vec3 listenerPos) {
		if (!shouldEvaluateDirection() || directions.isEmpty()) {
			return null;
		}
		Vec3 sum = direct != null ? direct.normalize() : Vec3.ZERO;
		for (int i = 0; i < directions.size(); i++) {
			double length = lengths.get(i);
			if (length <= 0) {
				return null;
			}
			sum = sum.add(directions.get(i).normalize().scale(1 / (length * length)));
		}
		Vec3 normalized = sum.normalize();
		return normalized.length() < 0.5 ? null : normalized.scale(soundPos.distanceTo(listenerPos)).add(listenerPos);
	}
}
