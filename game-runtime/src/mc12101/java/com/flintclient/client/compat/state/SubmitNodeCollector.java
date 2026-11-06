package com.flintclient.client.compat.state;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * What Flint's features hand 1.21.9's {@code SubmitNodeCollector}, drawn at once into a 1.21.1 buffer source (Minecraft
 * 1.21.1 draws as it goes; there is nothing to collect). Each call keeps the order it is made in.
 */
public final class SubmitNodeCollector {
	private final MultiBufferSource buffers;

	public SubmitNodeCollector(MultiBufferSource buffers) {
		this.buffers = buffers;
	}

	/** Draw order: 1.21.1 draws in call order already. */
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
	public void submitText(PoseStack pose, float x, float y, net.minecraft.util.FormattedCharSequence text, boolean shadow, Font.DisplayMode mode,
		int light, int color, int backgroundColor, int outlineColor) {
		Font font = Minecraft.getInstance().font;
		Matrix4f matrix = pose.last().pose();
		if (outlineColor != 0) {
			font.drawInBatch8xOutline(text, x, y, color, outlineColor, matrix, buffers, light);
		} else {
			font.drawInBatch(text, x, y, color, shadow, matrix, buffers, mode, backgroundColor, light);
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
		pose.mulPose(camera.orientation);
		pose.scale(0.025F, -0.025F, 0.025F);
		Matrix4f matrix = pose.last().pose();
		float x = -font.width(text) / 2.0F;
		int background = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
		font.drawInBatch(text, x, y, 0x20FFFFFF, false, matrix, buffers, seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL,
			background, light);
		if (seeThrough) {
			font.drawInBatch(text, x, y, 0xFFFFFFFF, false, matrix, buffers, Font.DisplayMode.NORMAL, 0, light);
		}
		pose.popPose();
	}

	/** Writes custom geometry, as 1.21.9's {@code SubmitNodeCollector.CustomGeometryRenderer}. */
	@FunctionalInterface
	public interface CustomGeometryRenderer {
		void render(PoseStack.Pose pose, VertexConsumer buffer);
	}
}
