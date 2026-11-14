package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.compat.state.SubmitNodeCollector;
import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.CosmeticModel;
import com.flintclient.client.cosmetics.ModelItem;
import com.flintclient.client.cosmetics.ModelPose;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * The player renderer's layer for 3D cosmetics on Minecraft 1.19.2 and 1.19 (added by
 * {@code mixin/cosmetics/AvatarRendererCosmeticsMixin}, after Minecraft's own), as on 26.3: every model a player wears
 * ({@link Worn}) on the part it hangs from, posed as that part is this frame (sneaking, swimming, gliding, riding: Minecraft
 * has posed the model before its layers draw), and an aura around the player unturned by the body. A spectator's layers
 * aren't drawn (Minecraft's rule); an invisible player shows none either. Shields are drawn with the held item
 * ({@code ShieldSpecialRendererCosmeticsMixin}). Headwear isn't drawn on you seen from your own eyes (a first-person body
 * mod), where it would block the view. 1.19's player model has no root part (its parts hang from the model's origin).
 */
public final class CosmeticsLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
	private static final int SHIELD = indexOf(CosmeticKind.SHIELD);
	private static final int HEADWEAR = indexOf(CosmeticKind.HEADWEAR);
	private static final int AURA = indexOf(CosmeticKind.AURA);

	public CosmeticsLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
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
	public void render(PoseStack poseStack, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
		float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
		Worn worn = ((CosmeticsState) player).flint$worn();
		if (worn == null || player.isInvisible() || !Mc263Cosmetics.GUARD.on()) {
			return;
		}
		double dist = Minecraft.getInstance().getEntityRenderDispatcher().distanceToSqr(player);
		draw(getParentModel(), worn, poseStack, new SubmitNodeCollector(buffers), light, dist, player.getMainHandItem().getItem() instanceof net.minecraft.world.item.ShieldItem
			|| player.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem);
	}

	/**
	 * Draws what {@code worn} wears on {@code model} (posed for this frame), {@code distSq} from the camera: the world's players,
	 * and the wardrobe's preview player (McCompat.previewAvatar, which has no entity).
	 */
	static void draw(PlayerModel<?> model, Worn worn, PoseStack poseStack, SubmitNodeCollector collector, int light, double distSq, boolean holdsShield) {
		double maxSq = Mc263Cosmetics.DRAW_DISTANCE * Mc263Cosmetics.DRAW_DISTANCE;
		if (distSq > maxSq) {
			return;
		}
		long began = System.nanoTime();
		try {
			boolean animate = distSq < Mc263Cosmetics.ANIMATE_DISTANCE * Mc263Cosmetics.ANIMATE_DISTANCE;
			// Detail by distance: everything up close, then only faces big enough to cover a pixel or more there.
			float minArea = distSq < 12 * 12 ? 0 : distSq < 24 * 24 ? 1.5F : 4F;
			boolean self = worn.id.equals(Mc263Cosmetics.selfId());
			long now = System.nanoTime();
			long wall = System.currentTimeMillis();
			for (int k = 0; k < worn.instances.length; k++) {
				Worn.Instance inst = worn.instances[k];
				if (inst == null || inst.pose == null || k == HEADWEAR && worn.ownView) {
					continue;
				}
				// A shield is drawn with the held item, unless it is worn on an arm (its geometry hangs from one): then on that arm,
				// in place of the shield held, and only while one is.
				if (k == SHIELD && !(Mc263Shields.onArm(inst) && holdsShield)) {
					continue;
				}
				CosmeticModel m = inst.pose.model;
				int fit = self ? 0 : Mc263Cosmetics.budget(Math.min(m.vertices(), CosmeticModel.LOD_QUADS * 4));
				if (fit == 2) {
					// A crowd past twice the frame's budget: the rest wait for the next frame's room.
					continue;
				}
				pose(inst, worn.motion, now, animate && fit == 0);
				// Another player's copy draws at most CosmeticModel.LOD_QUADS faces (its biggest); yours, all of them.
				float detail = Math.max(fit == 1 ? Math.max(minArea, 16F) : minArea, self ? 0 : m.lodArea);
				ModelItem item = inst.loaded.item;
				frame(inst, item, wall);
				for (int a = 0; a < CosmeticModel.ANCHORS; a++) {
					if ((m.anchorsUsed & 1 << a) == 0) {
						continue;
					}
					poseStack.pushPose();
					if (k != AURA || a != CosmeticModel.BODY) {
						part(model, a).translateAndRotate(poseStack);
					}
					Worn.Drawer d = inst.drawers[a];
					d.light = light;
					d.overlay = OverlayTexture.NO_OVERLAY;
					d.minArea = detail;
					collector.submitCustomGeometry(poseStack, inst.renderType, d);
					poseStack.popPose();
				}
			}
		} catch (RuntimeException | LinkageError e) {
			Mc263Cosmetics.failed(e);
		}
		Mc263Cosmetics.submitNanos += System.nanoTime() - began;
	}

	/** Poses {@code inst} for this frame (once a frame at most, within the budget and distance; else it keeps its last pose). */
	static void pose(Worn.Instance inst, int motion, long now, boolean animate) {
		if (inst.pose == null || !inst.animated && inst.posedAt != Long.MIN_VALUE) {
			return;
		}
		if (now - inst.posedAt < 2_000_000L || inst.posedAt != Long.MIN_VALUE && (!animate || !Mc263Cosmetics.mayPose())) {
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
		for (Worn.Drawer d : inst.drawers) {
			d.frameScale = scale;
			d.frameV = frame * scale;
		}
	}

	static ModelPart part(PlayerModel<?> model, int anchor) {
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
