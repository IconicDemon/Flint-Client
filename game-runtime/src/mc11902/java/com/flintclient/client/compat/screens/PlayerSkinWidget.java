package com.flintclient.client.compat.screens;

import com.flintclient.client.compat.PlayerSkin;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.util.Mth;

/**
 * 1.20.2's {@code PlayerSkinWidget} on Minecraft 1.19.2: a player's model in their skin (wide or slim arms as the skin says),
 * standing in the widget's box, turned by dragging. Drawn as 1.20.2 draws it, with 1.19.2's PoseStack, rotations and buffers:
 * the model straight into the screen's pose, lit by the inventory's lights turned with the model's tilt (1.20.2's lights, which
 * light the model in the screen's pose; 1.19.2's own are for its inventory's flipped view), and drawn as Fancy (as 1.19.2's
 * inventory draws a player: Fabulous would send the translucent player to the world's item target).
 */
public final class PlayerSkinWidget extends AbstractWidget {
	private static final float MODEL_OFFSET = 0.0625F;
	private static final float MODEL_HEIGHT = 2.125F;
	private static final float Z_OFFSET = 100.0F;
	private static final float ROTATION_SENSITIVITY = 2.5F;
	private static final float DEFAULT_ROTATION_X = -5.0F;
	private static final float DEFAULT_ROTATION_Y = 30.0F;
	private static final float ROTATION_X_LIMIT = 50.0F;
	/** 1.20.2's inventory lights (Lighting.INVENTORY_DIFFUSE_LIGHT_0 and _1). */
	private static final Vector3f LIGHT_0 = normalized(0.2F, -1.0F, 1.0F);
	private static final Vector3f LIGHT_1 = normalized(-0.2F, -1.0F, 0.0F);
	private static final int FULL_BRIGHT = 15728880;

	private final PlayerModel<?> wideModel;
	private final PlayerModel<?> slimModel;
	private final Supplier<PlayerSkin> skin;
	private float rotationX = DEFAULT_ROTATION_X;
	private float rotationY = DEFAULT_ROTATION_Y;

	public PlayerSkinWidget(int width, int height, EntityModelSet models, Supplier<PlayerSkin> skin) {
		super(0, 0, width, height, CommonComponents.EMPTY);
		this.wideModel = new PlayerModel<>(models.bakeLayer(ModelLayers.PLAYER), false);
		this.slimModel = new PlayerModel<>(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
		this.wideModel.young = false;
		this.slimModel.young = false;
		this.skin = skin;
	}

	@Override
	public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
		pose.pushPose();
		pose.translate(x + width / 2.0F, y + height, Z_OFFSET);
		float scale = height / MODEL_HEIGHT;
		pose.scale(scale, scale, scale);
		pose.translate(0.0F, -MODEL_OFFSET, 0.0F);
		// 1.20.2's rotateAround: the tilt turns about the model's middle.
		pose.translate(0.0F, -1.0625F, 0.0F);
		pose.mulPose(Vector3f.XP.rotationDegrees(rotationX));
		pose.translate(0.0F, 1.0625F, 0.0F);
		pose.mulPose(Vector3f.YP.rotationDegrees(rotationY));
		MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
		buffers.endBatch();
		Quaternion tilt = Vector3f.XP.rotationDegrees(rotationX);
		RenderSystem.setShaderLights(turned(LIGHT_0, tilt), turned(LIGHT_1, tilt));
		PlayerSkin current = skin.get();
		pose.pushPose();
		pose.scale(1.0F, 1.0F, -1.0F);
		pose.translate(0.0F, -1.5F, 0.0F);
		PlayerModel<?> model = current.model() == PlayerSkin.Model.SLIM ? slimModel : wideModel;
		RenderSystem.runAsFancy(() -> {
			model.renderToBuffer(pose, buffers.getBuffer(model.renderType(current.texture())), FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F,
				1.0F, 1.0F);
			buffers.endBatch();
		});
		pose.popPose();
		com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
		pose.popPose();
	}

	@Override
	protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
		rotationX = Mth.clamp(rotationX - (float) dragY * ROTATION_SENSITIVITY, -ROTATION_X_LIMIT, ROTATION_X_LIMIT);
		rotationY += (float) dragX * ROTATION_SENSITIVITY;
	}

	@Override
	public void playDownSound(SoundManager sounds) {
	}

	/** Not reached with Tab (1.20.2's nextFocusPath answers none). */
	@Override
	public boolean changeFocus(boolean forward) {
		return false;
	}

	@Override
	public void updateNarration(NarrationElementOutput output) {
	}

	private static Vector3f normalized(float x, float y, float z) {
		Vector3f v = new Vector3f(x, y, z);
		v.normalize();
		return v;
	}

	private static Vector3f turned(Vector3f light, Quaternion rotation) {
		Vector3f v = light.copy();
		v.transform(rotation);
		return v;
	}
}
