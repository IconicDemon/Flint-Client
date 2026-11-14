package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.CosmeticModel;
import com.flintclient.client.cosmetics.ModelItem;
import com.flintclient.client.cosmetics.ModelPose;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * The player renderer's layer for 3D cosmetics (added by {@code mixin/cosmetics/AvatarRendererCosmeticsMixin}, after
 * Minecraft's own): every model a player wears ({@link Worn}) on the part it hangs from, posed as that part is this frame
 * (sneaking, swimming, gliding, riding: Minecraft has posed the model before its layers draw), and an aura around the player
 * unturned by the body. A spectator's layers aren't drawn (Minecraft's rule); an invisible player shows none either. Shields
 * are drawn with the held item ({@code ShieldSpecialRendererCosmeticsMixin}). Headwear isn't drawn on you seen from your own
 * eyes (a first-person body mod), where it would block the view.
 */
public final class CosmeticsLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final int SHIELD = indexOf(CosmeticKind.SHIELD);
	private static final int HEADWEAR = indexOf(CosmeticKind.HEADWEAR);
	private static final int AURA = indexOf(CosmeticKind.AURA);
	/** Blocks: nearer than this a model is posed every frame (to 500 a second); farther, 60 times a second, as smooth there. */
	static final double SMOOTH_DISTANCE = 12;
	private static final long FAR_POSE_NANOS = 16_000_000L;

	public CosmeticsLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	private static int indexOf(CosmeticKind kind) {
		for (int k = 0; k < CosmeticKind.WORN.length; k++) {
			if (CosmeticKind.WORN[k] == kind) {
				return k;
			}
		}
		return -1;
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
		Worn worn = ((CosmeticsState) state).flint$worn();
		if (worn == null || state.isInvisible || !Mc263Cosmetics.GUARD.on()) {
			return;
		}
		double maxSq = Mc263Cosmetics.DRAW_DISTANCE * Mc263Cosmetics.DRAW_DISTANCE;
		if (state.distanceToCameraSq > maxSq) {
			return;
		}
		long began = System.nanoTime();
		long allocated = Mc263Cosmetics.allocBegin();
		try {
			boolean animate = state.distanceToCameraSq < Mc263Cosmetics.ANIMATE_DISTANCE * Mc263Cosmetics.ANIMATE_DISTANCE;
			// Detail by distance: everything up close, then only faces big enough to cover a pixel or more there.
			double dist = state.distanceToCameraSq;
			float minArea = dist < 12 * 12 ? 0 : dist < 24 * 24 ? 1.5F : 4F;
			boolean self = worn.id.equals(Mc263Cosmetics.selfId());
			long now = System.nanoTime();
			long wall = System.currentTimeMillis();
			PlayerModel model = getParentModel();
			for (int k = 0; k < worn.instances.length; k++) {
				Worn.Instance inst = worn.instances[k];
				if (inst == null || inst.pose == null || k == HEADWEAR && worn.ownView) {
					continue;
				}
				// A shield is drawn with the held item, unless it is worn on an arm (its geometry hangs from one): then on that arm,
				// in place of the shield held, and only while one is.
				if (k == SHIELD && !(Mc263Shields.onArm(inst) && Mc263Shields.holding(state))) {
					continue;
				}
				CosmeticModel m = inst.pose.model;
				int fit = self ? 0 : Mc263Cosmetics.budget(Math.min(m.vertices(), CosmeticModel.LOD_QUADS * 4));
				if (fit == 2) {
					// A crowd past twice the frame's budget: the rest wait for the next frame's room.
					continue;
				}
				pose(inst, worn.motion, now, animate && fit == 0, dist < SMOOTH_DISTANCE * SMOOTH_DISTANCE ? 2_000_000L : FAR_POSE_NANOS);
				// Another player's copy draws at most CosmeticModel.LOD_QUADS faces (its biggest); yours, all of them.
				float detail = Math.max(fit == 1 ? Math.max(minArea, 16F) : minArea, self ? 0 : m.lodArea);
				ModelItem item = inst.loaded.item;
				frame(inst, item, wall);
				for (int a = 0; a < CosmeticModel.ANCHORS; a++) {
					if ((m.anchorsUsed & 1 << a) == 0) {
						continue;
					}
					poseStack.pushPose();
					model.root().translateAndRotate(poseStack);
					if (k != AURA || a != CosmeticModel.BODY) {
						part(model, a).translateAndRotate(poseStack);
					}
					inst.submit(collector, poseStack, a, light, OverlayTexture.NO_OVERLAY, detail);
					poseStack.popPose();
				}
			}
		} catch (RuntimeException | LinkageError e) {
			Mc263Cosmetics.failed(e);
		}
		Mc263Cosmetics.allocEndSubmit(allocated);
		Mc263Cosmetics.submitNanos += System.nanoTime() - began;
	}

	/** Poses {@code inst} for this frame (once a frame at most, within the budget and distance; else it keeps its last pose). */
	static void pose(Worn.Instance inst, int motion, long now, boolean animate) {
		pose(inst, motion, now, animate, 2_000_000L);
	}

	/** As {@link #pose(Worn.Instance, int, long, boolean)}, at most once every {@code every} nanoseconds. */
	static void pose(Worn.Instance inst, int motion, long now, boolean animate, long every) {
		if (inst.pose == null || !inst.animated && inst.posedAt != Long.MIN_VALUE) {
			return;
		}
		if (now - inst.posedAt < every || inst.posedAt != Long.MIN_VALUE && (!animate || !Mc263Cosmetics.mayPose())) {
			return;
		}
		inst.posedAt = now;
		double seconds = inst.seconds(now);
		inst.pose.pose(motion, seconds, seconds);
	}

	/** The texture frame {@code item} shows at {@code wall} (the wall clock, so every wearer of it shows the same frame). */
	static void frame(Worn.Instance inst, ModelItem item, long wall) {
		int frames = item.frames();
		float scale = 1F / frames;
		int frame = 0;
		if (frames > 1) {
			long cycle = item.cycleMs();
			long t = cycle <= 0 ? 0 : wall % cycle;
			while (frame < frames - 1 && t >= item.frameMs(frame)) {
				t -= item.frameMs(frame);
				frame++;
			}
		}
		frame(inst.drawers, scale, frame);
		Worn.Drawer[] more = inst.blendDrawers;
		if (more != null) {
			frame(more, scale, frame);
		}
	}

	private static void frame(Worn.Drawer[] drawers, float scale, int frame) {
		for (Worn.Drawer d : drawers) {
			d.frameScale = scale;
			d.frameV = frame * scale;
		}
	}

	static ModelPart part(PlayerModel model, int anchor) {
		return switch (anchor) {
			case CosmeticModel.HEAD -> model.head;
			case CosmeticModel.RIGHT_ARM -> model.rightArm;
			case CosmeticModel.LEFT_ARM -> model.leftArm;
			case CosmeticModel.RIGHT_LEG -> model.rightLeg;
			case CosmeticModel.LEFT_LEG -> model.leftLeg;
			default -> model.body;
		};
	}

	/** The motion clip names, for the dev tour. */
	static String motion(int m) {
		return switch (m) {
			case ModelPose.WALK -> "walk";
			case ModelPose.FLY -> "fly";
			case ModelPose.SWIM -> "swim";
			default -> "idle";
		};
	}
}
