package com.flintclient.legacy.cosmetics;

import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.CosmeticModel;
import com.flintclient.client.cosmetics.ModelItem;
import com.flintclient.client.platform.Texture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.client.render.entity.layer.EntityRenderLayer;
import net.minecraft.client.render.model.ModelPart;
import net.minecraft.client.render.model.entity.PlayerModel;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;

/**
 * The player renderer's layer for 3D cosmetics on 1.8.9 (26.3's {@code CosmeticsLayer}; added by
 * {@code mixin/cosmetics/PlayerRendererCosmeticsMixin}, after Minecraft's own): every model a player wears ({@link Worn189}) on
 * the part it hangs from, posed as that part is this frame (Minecraft has posed the model before its layers draw), and an aura
 * around the player unturned by the body. An invisible or spectating player shows none. 1.8.9 has no shield item: a shield
 * worn on an arm shows on that arm while the player blocks with a sword (1.8.9's raised guard). Also draws the wardrobe's
 * preview player's ({@link #draw} with no entity).
 */
public final class CosmeticsLayer189 implements EntityRenderLayer<ClientPlayerEntity> {
	private static final int SHIELD = indexOf(CosmeticKind.SHIELD);
	private static final int AURA = indexOf(CosmeticKind.AURA);
	private final PlayerModel model;

	public CosmeticsLayer189(PlayerModel model) {
		this.model = model;
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
	public void render(ClientPlayerEntity player, float walkProgress, float walkSpeed, float tickDelta, float bob, float yaw, float pitch, float scale) {
		if (player.isInvisible() || player.isSpectator() || !Cosmetics189.GUARD.on()) {
			return;
		}
		Worn189 worn = Cosmetics189.worn(player);
		if (worn == null) {
			return;
		}
		Entity camera = Minecraft.getInstance().getEntityRenderDispatcher().camera;
		double distSq = camera == null || Cosmetics189.inDoll() ? 0 : player.squaredDistanceTo(camera);
		if (distSq > Cosmetics189.DRAW_DISTANCE * Cosmetics189.DRAW_DISTANCE) {
			return;
		}
		boolean self = worn.id.equals(Cosmetics189.selfId());
		draw(model, worn, distSq, self, player.isSneaking(), player.isSwordBlocking());
	}

	/** Draws what {@code worn} has on {@code model} (posed already), OpenGL's model view at the model's origin. */
	static void draw(PlayerModel model, Worn189 worn, double distSq, boolean self, boolean sneaking, boolean blocking) {
		long began = System.nanoTime();
		try {
			boolean animate = distSq < Cosmetics189.ANIMATE_DISTANCE * Cosmetics189.ANIMATE_DISTANCE;
			float minArea = distSq < 12 * 12 ? 0 : distSq < 24 * 24 ? 1.5F : 4F;
			long now = System.nanoTime();
			long wall = System.currentTimeMillis();
			GlStateManager.color4f(1, 1, 1, 1);
			GlStateManager.enableRescaleNormal();
			for (int k = 0; k < worn.instances.length; k++) {
				Worn189.Instance inst = worn.instances[k];
				if (inst == null || inst.pose == null) {
					continue;
				}
				CosmeticModel m = inst.pose.model;
				if (k == SHIELD && !(blocking && (m.anchorsUsed & (1 << CosmeticModel.LEFT_ARM | 1 << CosmeticModel.RIGHT_ARM)) != 0)) {
					continue;
				}
				int fit = self ? 0 : Cosmetics189.budget(Math.min(m.vertices(), CosmeticModel.LOD_QUADS * 4));
				if (fit == 2) {
					continue;
				}
				pose(inst, worn.motion, now, animate && fit == 0);
				float detail = Math.max(fit == 1 ? Math.max(minArea, 16F) : minArea, self ? 0 : m.lodArea);
				frame(inst, inst.loaded.item, wall);
				Texture texture = inst.loaded.texture;
				if (texture == null) {
					continue;
				}
				GlStateManager.bindTexture(texture.glId());
				boolean blend = inst.loaded.translucent;
				if (blend) {
					GlStateManager.enableBlend();
					GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
				}
				for (int a = 0; a < CosmeticModel.ANCHORS; a++) {
					if ((m.anchorsUsed & 1 << a) == 0) {
						continue;
					}
					GlStateManager.pushMatrix();
					if (sneaking) {
						// As Minecraft draws a sneaking player's parts (HumanoidModel.render).
						GlStateManager.translatef(0.0F, 0.2F, 0.0F);
					}
					if (k != AURA || a != CosmeticModel.BODY) {
						part(model, a).transform(0.0625F);
					}
					Cosmetics189.vertices += inst.draw(a, detail);
					GlStateManager.popMatrix();
				}
				if (blend) {
					GlStateManager.disableBlend();
				}
			}
			GlStateManager.disableRescaleNormal();
		} catch (RuntimeException | LinkageError e) {
			Cosmetics189.failed(e);
		}
		Cosmetics189.drawNanos += System.nanoTime() - began;
	}

	static void pose(Worn189.Instance inst, int motion, long now, boolean animate) {
		if (inst.pose == null || !inst.animated && inst.posedAt != Long.MIN_VALUE) {
			return;
		}
		if (now - inst.posedAt < 2_000_000L || inst.posedAt != Long.MIN_VALUE && (!animate || !Cosmetics189.mayPose())) {
			return;
		}
		inst.posedAt = now;
		double seconds = inst.seconds(now);
		inst.pose.pose(motion, seconds, seconds);
	}

	static void frame(Worn189.Instance inst, ModelItem item, long wall) {
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
		inst.frameScale = scale;
		inst.frameV = frame * scale;
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

	@Override
	public boolean colorsWhenDamaged() {
		return true;
	}
}
