package com.flintclient.legacy.cosmetics;

import com.flintclient.client.cosmetics.EmotePose;
import net.minecraft.client.render.model.Model;
import net.minecraft.client.render.model.ModelPart;
import net.minecraft.client.render.model.entity.PlayerModel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.living.player.PlayerEntity;

/**
 * Lays an emote ({@link EmotePose}) over the pose Minecraft 1.8.9 gave the player model, right after its
 * {@code setupAnimation} (26.3's {@code EmoteModel}): the parts it animates take its rotation and offset, and the sleeves,
 * jacket, trousers and hat (separate parts on 1.8.9) are put with them again. 1.8.9's setup leaves some part positions as they
 * were (the head's, the body's, the legs' sideways), so a model an emote moved is put back first: the model is shared by
 * every player drawn. Render thread.
 */
public final class EmoteModel189 {
	private static final float[] PARTS = new float[EmotePose.PARTS * EmotePose.STRIDE];
	/** Models an emote moved last (their positions are put back before the next pose). */
	private static final java.util.Set<PlayerModel> MOVED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	private EmoteModel189() {
	}

	/** After {@code setupAnimation} posed {@code model} for {@code entity}. */
	public static void afterSetup(PlayerModel model, Entity entity, float walkSpeed) {
		if (MOVED.remove(model)) {
			model.head.x = 0;
			model.head.z = 0;
			model.body.setPos(0, 0, 0);
			model.rightLeg.x = -1.9F;
			model.leftLeg.x = 1.9F;
			copyWear(model);
		}
		if (!(entity instanceof PlayerEntity player) || !Cosmetics189.GUARD.on()) {
			return;
		}
		Worn189 w = Cosmetics189.worn(player);
		if (w == null) {
			return;
		}
		w.motion = Cosmetics189.motion(player, walkSpeed);
		if (w.emote != null) {
			apply(model, w);
			copyWear(model);
		}
	}

	/** {@code w}'s emote on {@code model} (the wear parts follow only through {@link #copyWear}). */
	static void apply(PlayerModel model, Worn189 w) {
		if (w.emote == null) {
			return;
		}
		try {
			EmotePose pose = w.emotePose;
			pose.sample(w.emote, w.emoteSeconds, w.emoteSeconds);
			if (!pose.any()) {
				return;
			}
			ModelPart[] parts = {model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
			for (int p = 0; p < parts.length; p++) {
				int o = p * EmotePose.STRIDE;
				PARTS[o] = parts[p].x;
				PARTS[o + 1] = parts[p].y;
				PARTS[o + 2] = parts[p].z;
				PARTS[o + 3] = parts[p].rotationX;
				PARTS[o + 4] = parts[p].rotationY;
				PARTS[o + 5] = parts[p].rotationZ;
			}
			pose.apply(PARTS, w.emoteWeight);
			for (int p = 0; p < parts.length; p++) {
				int o = p * EmotePose.STRIDE;
				parts[p].x = PARTS[o];
				parts[p].y = PARTS[o + 1];
				parts[p].z = PARTS[o + 2];
				parts[p].rotationX = PARTS[o + 3];
				parts[p].rotationY = PARTS[o + 4];
				parts[p].rotationZ = PARTS[o + 5];
			}
			MOVED.add(model);
		} catch (RuntimeException | LinkageError e) {
			Cosmetics189.failed(e);
		}
	}

	private static void copyWear(PlayerModel model) {
		Model.copyRotation(model.head, model.hat);
		Model.copyRotation(model.body, model.jacket);
		Model.copyRotation(model.rightArm, model.rightSleeve);
		Model.copyRotation(model.leftArm, model.leftSleeve);
		Model.copyRotation(model.rightLeg, model.rightPants);
		Model.copyRotation(model.leftLeg, model.leftPants);
	}
}
