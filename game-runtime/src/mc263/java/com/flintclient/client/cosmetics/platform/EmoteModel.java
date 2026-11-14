package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.cosmetics.EmotePose;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * Lays an emote ({@link EmotePose}) over the pose Minecraft gave the player model, right after {@code setupAnim}: the parts it
 * animates take its rotation and offset (their sleeves, jacket, trousers and hat are their children and follow). The model is
 * Minecraft's shared one, posed again for every player drawn, so nothing carries over to the next. Render thread.
 */
public final class EmoteModel {
	private static final float[] PARTS = new float[EmotePose.PARTS * EmotePose.STRIDE];
	private static PlayerModel lastModel;
	private static ModelPart[] lastParts = new ModelPart[0];

	private EmoteModel() {
	}

	public static void apply(PlayerModel model, AvatarRenderState state) {
		Worn w = ((CosmeticsState) state).flint$worn();
		if (w == null || w.emote == null || !Mc263Cosmetics.GUARD.on()) {
			return;
		}
		long began = System.nanoTime();
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
		} catch (RuntimeException | LinkageError e) {
			Mc263Cosmetics.failed(e);
		} finally {
			Mc263Cosmetics.emoteNanos += System.nanoTime() - began;
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
