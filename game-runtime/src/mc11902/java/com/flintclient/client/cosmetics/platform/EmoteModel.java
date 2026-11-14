package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.cosmetics.EmotePose;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import org.jspecify.annotations.Nullable;

/**
 * Lays an emote ({@link EmotePose}) over the pose Minecraft gave the player model, right after {@code setupAnim}: the parts it
 * animates take its rotation and offset. On 1.19 the sleeves, jacket, trousers and hat are the model's own parts copied from
 * their limbs inside setupAnim, so they are copied again after the emote. The model is Minecraft's shared one, posed again for
 * every player drawn, so nothing carries over to the next. Render thread.
 */
public final class EmoteModel {
	private static final float[] PARTS = new float[EmotePose.PARTS * EmotePose.STRIDE];
	private static @Nullable PlayerModel<?> lastModel;
	private static ModelPart[] lastParts = new ModelPart[0];

	private EmoteModel() {
	}

	/** {@code worn}: the player's ({@link CosmeticsState} of the entity being drawn, or the wardrobe's preview player). */
	public static void apply(PlayerModel<?> model, @Nullable Worn w) {
		if (w == null || w.emote == null || !Mc263Cosmetics.GUARD.on()) {
			return;
		}
		try {
			EmotePose pose = w.emotePose;
			pose.sample(w.emote, w.emoteSeconds, w.emoteSeconds);
			if (!pose.any()) {
				return;
			}
			if (model != lastModel) {
				lastModel = model;
				lastParts = new ModelPart[] {model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
			}
			ModelPart[] parts = lastParts;
			for (int p = 0; p < parts.length; p++) {
				read(parts[p], p);
			}
			pose.apply(PARTS, w.emoteWeight);
			for (int p = 0; p < parts.length; p++) {
				write(parts[p], p);
			}
			// 1.19's outer layer follows its limb by a copy setupAnim made before the emote.
			model.hat.copyFrom(model.head);
			model.jacket.copyFrom(model.body);
			model.leftSleeve.copyFrom(model.leftArm);
			model.rightSleeve.copyFrom(model.rightArm);
			model.leftPants.copyFrom(model.leftLeg);
			model.rightPants.copyFrom(model.rightLeg);
		} catch (RuntimeException | LinkageError e) {
			Mc263Cosmetics.failed(e);
		}
	}

	private static void read(ModelPart part, int p) {
		int o = p * EmotePose.STRIDE;
		PARTS[o] = part.x;
		PARTS[o + 1] = part.y;
		PARTS[o + 2] = part.z;
		PARTS[o + 3] = part.xRot;
		PARTS[o + 4] = part.yRot;
		PARTS[o + 5] = part.zRot;
	}

	private static void write(ModelPart part, int p) {
		int o = p * EmotePose.STRIDE;
		part.x = PARTS[o];
		part.y = PARTS[o + 1];
		part.z = PARTS[o + 2];
		part.xRot = PARTS[o + 3];
		part.yRot = PARTS[o + 4];
		part.zRot = PARTS[o + 5];
	}
}
