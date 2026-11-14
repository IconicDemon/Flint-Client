package com.flintclient.client.compat;

import com.flintclient.client.compat.state.SubmitNodeCollector;
import com.flintclient.client.cosmetics.platform.EmoteModel;
import com.flintclient.client.cosmetics.platform.Mc263Cosmetics;
import com.flintclient.client.cosmetics.platform.Worn;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * A whole 3D player drawn in a box of a screen on Minecraft 1.19.2 and 1.19, without an entity (the wardrobe's preview player,
 * the Discord card's): 1.19 draws players only from a living entity, so Flint poses and draws the player model itself, as
 * 1.19's inventory doll and player renderer do (the same turn, flip, scale and light), wearing a skin, a cape and, while the
 * wardrobe draws it, the 3D cosmetics it tries on, with your emote playing ({@link Mc263Cosmetics#preview}). It looks as 26.3's
 * {@code GuiGraphics.entity} draws a player render state: centred in the box, the feet near its bottom, tilted 6 degrees.
 * Render thread.
 */
public final class PreviewAvatar {
	private static final int FULL_BRIGHT = 15728880;
	private static @Nullable PlayerModel<AbstractClientPlayer> wide;
	private static @Nullable PlayerModel<AbstractClientPlayer> slim;

	private PreviewAvatar() {
	}

	private static PlayerModel<AbstractClientPlayer> model(boolean slimArms) {
		PlayerModel<AbstractClientPlayer> m = slimArms ? slim : wide;
		if (m == null) {
			ModelPart root = Minecraft.getInstance().getEntityModels().bakeLayer(slimArms ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER);
			m = new PlayerModel<>(root, slimArms);
			if (slimArms) {
				slim = m;
			} else {
				wide = m;
			}
		}
		return m;
	}

	/** Back to the model's standing pose, as setupAnim leaves a player idle at its first tick (the arms a little out). */
	private static void stand(PlayerModel<?> m) {
		for (ModelPart p : new ModelPart[] {m.head, m.hat, m.body, m.jacket, m.rightArm, m.rightSleeve, m.leftArm, m.leftSleeve, m.rightLeg,
			m.rightPants, m.leftLeg, m.leftPants}) {
			p.resetPose();
			p.visible = true;
		}
		// HumanoidModel's idle arm sway (AnimationUtils.bobArms) at age 0.
		m.rightArm.zRot += 0.1F;
		m.leftArm.zRot -= 0.1F;
		m.rightSleeve.copyFrom(m.rightArm);
		m.leftSleeve.copyFrom(m.leftArm);
		m.young = false;
		m.crouching = false;
		m.riding = false;
	}

	/**
	 * Draws the player in the GUI box ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}) of {@code graphics} (its pose as it is),
	 * {@code skin} on the {@code slimArms} model, {@code cape} (null: none) leaning {@code capeLean} degrees, turned {@code yaw}
	 * degrees from facing the viewer.
	 */
	public static void draw(GuiGraphics graphics, ResourceLocation skin, boolean slimArms, @Nullable ResourceLocation cape, int x0, int y0, int x1,
		int y1, float yaw, float capeLean) {
		Minecraft mc = Minecraft.getInstance();
		PlayerModel<AbstractClientPlayer> model = model(slimArms);
		stand(model);
		Worn worn = Mc263Cosmetics.preview();
		EmoteModel.apply(model, worn);
		float size = (y1 - y0) / 2.1F;
		graphics.flush();
		// The scissor is in screen GUI units: the box as the screen's transform places it (the menu may be moved or scaled).
		org.joml.Matrix3x2f at = graphics.pose2d();
		graphics.enableScissor(Math.round(at.m00() * x0 + at.m20()), Math.round(at.m11() * y0 + at.m21()), Math.round(at.m00() * x1 + at.m20()),
			Math.round(at.m11() * y1 + at.m21()));
		PoseStack view = RenderSystem.getModelViewStack();
		view.pushPose();
		try {
			// The screen's own transform (the menu's place and scale), then the box's centre, as the inventory doll is placed.
			Matrix4f screen = graphics.pose().last().pose();
			view.mulPoseMatrix(screen);
			view.translate((x0 + x1) / 2.0F, (y0 + y1) / 2.0F, 1050.0F);
			view.scale(1.0F, 1.0F, -1.0F);
			RenderSystem.applyModelViewMatrix();
			PoseStack pose = new PoseStack();
			pose.translate(0.0F, 0.0F, 1000.0F);
			pose.scale(size, size, size);
			pose.translate(0.0F, 0.95F, 0.0F);
			pose.mulPose(Vector3f.ZP.rotationDegrees(180.0F));
			pose.mulPose(Vector3f.XP.rotationDegrees(-6.0F));
			Lighting.setupForEntityInInventory();
			// As LivingEntityRenderer turns, flips and scales a player before drawing its model.
			pose.mulPose(Vector3f.YP.rotationDegrees(-yaw));
			pose.scale(-1.0F, -1.0F, 1.0F);
			pose.scale(0.9375F, 0.9375F, 0.9375F);
			pose.translate(0.0F, -1.501F, 0.0F);
			MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
			model.renderToBuffer(pose, buffers.getBuffer(model.renderType(skin)), FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
			if (cape != null && !Mc263Cosmetics.hidesCape(worn)) {
				// As 1.19's CapeLayer hangs it, standing still.
				pose.pushPose();
				pose.translate(0.0F, 0.0F, 0.125F);
				pose.mulPose(Vector3f.XP.rotationDegrees(6.0F + capeLean / 2.0F));
				pose.mulPose(Vector3f.YP.rotationDegrees(180.0F));
				model.renderCloak(pose, buffers.getBuffer(RenderType.entitySolid(cape)), FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
				pose.popPose();
			}
			if (worn != null) {
				Mc263Cosmetics.drawPreview(model, worn, pose, new SubmitNodeCollector(buffers), FULL_BRIGHT);
			}
			buffers.endBatch();
			// What the screen draws next goes over the player, in the order asked (as the rest of 1.19's GUI here), not behind its depth.
			RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
		} finally {
			view.popPose();
			RenderSystem.applyModelViewMatrix();
			Lighting.setupFor3DItems();
			graphics.disableScissor();
		}
	}
}
