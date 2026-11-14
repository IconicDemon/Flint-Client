package com.flintclient.client.compat.state;

import com.flintclient.client.compat.render.DisplayMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Matrix4f;
import com.mojang.math.Quaternion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * What Flint's features hand 1.21.9's {@code SubmitNodeCollector}, drawn at once into a Minecraft 1.19.2 buffer source
 * (1.19.2 draws as it goes; there is nothing to collect). Each call keeps the order it is made in. 1.19.2's font takes its own
 * matrix and a see-through flag (compat.render.DisplayMode stands in for the display mode), its pose stack its own quaternion.
 */
public final class SubmitNodeCollector {
	/** The camera's turn as 1.19.2's pose stack takes it (render thread; mulPose doesn't keep it). */
	private static final Quaternion TURN = new Quaternion(0.0F, 0.0F, 0.0F, 1.0F);

	private final MultiBufferSource buffers;

	public SubmitNodeCollector(MultiBufferSource buffers) {
		this.buffers = buffers;
	}

	/** Draw order: 1.19.2 draws in call order already. */
	public SubmitNodeCollector order(int order) {
		return this;
	}

	/** Geometry Flint writes itself, into a buffer of {@code type}, at {@code pose}. */
	public void submitCustomGeometry(PoseStack pose, RenderType type, CustomGeometryRenderer renderer) {
		renderer.render(pose.last(), buffers.getBuffer(type));
	}

	/**
	 * A line of text at {@code x}, {@code y} in {@code pose}'s plane (1.21.9's submitText): its background behind it, or an
	 * outline around it when {@code outlineColor} isn't 0, drawn at once.
	 */
	public void submitText(PoseStack pose, float x, float y, net.minecraft.util.FormattedCharSequence text, boolean shadow, DisplayMode mode,
		int light, int color, int backgroundColor, int outlineColor) {
		Font font = Minecraft.getInstance().font;
		Matrix4f matrix = pose.last().pose();
		if (outlineColor != 0) {
			font.drawInBatch8xOutline(text, x, y, color, outlineColor, matrix, buffers, light);
		} else {
			font.drawInBatch(text, x, y, color, shadow, matrix, buffers, mode.seeThrough(), backgroundColor, light);
		}
	}

	/**
	 * A nametag-style line of text at {@code at} (relative to {@code pose}'s origin), {@code y} pixels down from it, facing the
	 * camera, as Minecraft draws a nametag: see-through behind blocks unless {@code discrete}, then solid on top.
	 */
	public void submitNameTag(PoseStack pose, Vec3 at, int y, Component text, boolean seeThrough, int light, double distanceSq,
		CameraRenderState camera) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		pose.pushPose();
		pose.translate(at.x, at.y + 0.5, at.z);
		TURN.set(camera.orientation.x(), camera.orientation.y(), camera.orientation.z(), camera.orientation.w());
		pose.mulPose(TURN);
		pose.scale(0.025F, -0.025F, 0.025F);
		Matrix4f matrix = pose.last().pose();
		float x = -font.width(text) / 2.0F;
		int background = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
		font.drawInBatch(text, x, y, 0x20FFFFFF, false, matrix, buffers, seeThrough, background, light);
		if (seeThrough) {
			font.drawInBatch(text, x, y, 0xFFFFFFFF, false, matrix, buffers, false, 0, light);
		}
		pose.popPose();
	}

	/** Writes custom geometry, as 1.21.9's {@code SubmitNodeCollector.CustomGeometryRenderer}. */
	@FunctionalInterface
	public interface CustomGeometryRenderer {
		void render(PoseStack.Pose pose, VertexConsumer buffer);
	}
}
