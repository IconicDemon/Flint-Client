package com.flintclient.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2fc;

/**
 * 26.x's GUI pose, a 2D {@link Matrix3x2fStack}, over Minecraft 1.21.1's {@link PoseStack}. The shared sources move,
 * scale and rotate the GUI through {@code graphics.pose()} as a 2D stack (the line's rules call it
 * {@code graphics.pose2d()}); every change is made to the GuiGraphics' PoseStack, which is what 1.21.1 draws with, and
 * this matrix mirrors its 2D part so it can be read (transformPosition, set from it). Pushes and pops are the
 * PoseStack's own, so they nest with Minecraft's.
 */
public final class Pose2D extends Matrix3x2fStack {
	private final PoseStack stack;
	private final Matrix4f scratch = new Matrix4f();
	private final Quaternionf turn = new Quaternionf();

	public Pose2D(PoseStack stack) {
		super(1);
		this.stack = stack;
		sync();
	}

	/** Reads the PoseStack's current 2D part (Minecraft may have moved it since the last call). */
	public Pose2D sync() {
		Matrix4f m = stack.last().pose();
		super.set(m.m00(), m.m01(), m.m10(), m.m11(), m.m30(), m.m31());
		return this;
	}

	public PoseStack stack() {
		return stack;
	}

	@Override
	public Matrix3x2fStack pushMatrix() {
		stack.pushPose();
		return this;
	}

	@Override
	public Matrix3x2fStack popMatrix() {
		stack.popPose();
		sync();
		return this;
	}

	@Override
	public Matrix3x2fStack clear() {
		sync();
		return this;
	}

	@Override
	public Matrix3x2f translate(float x, float y) {
		stack.translate(x, y, 0.0F);
		return sync();
	}

	@Override
	public Matrix3x2f translate(Vector2fc offset) {
		return translate(offset.x(), offset.y());
	}

	@Override
	public Matrix3x2f scale(float x, float y) {
		stack.scale(x, y, 1.0F);
		return sync();
	}

	@Override
	public Matrix3x2f scale(float xy) {
		return scale(xy, xy);
	}

	@Override
	public Matrix3x2f scale(Vector2fc factor) {
		return scale(factor.x(), factor.y());
	}

	@Override
	public Matrix3x2f rotate(float radians) {
		stack.mulPose(turn.identity().rotateZ(radians));
		return sync();
	}

	@Override
	public Matrix3x2f rotateAbout(float radians, float x, float y) {
		translate(x, y);
		rotate(radians);
		return translate(-x, -y);
	}

	@Override
	public Matrix3x2f mul(Matrix3x2fc m) {
		stack.last().pose().mul(scratch.set(m.m00(), m.m01(), 0, 0, m.m10(), m.m11(), 0, 0, 0, 0, 1, 0, m.m20(), m.m21(), 0, 1));
		return sync();
	}

	/** Makes the current pose's 2D part {@code m} (its depth part is kept). */
	@Override
	public Matrix3x2f set(Matrix3x2fc m) {
		Matrix4f pose = stack.last().pose();
		pose.m00(m.m00()).m01(m.m01()).m10(m.m10()).m11(m.m11()).m30(m.m20()).m31(m.m21());
		return sync();
	}

	@Override
	public Matrix3x2f identity() {
		return set(new Matrix3x2f());
	}
}
