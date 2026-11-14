package com.flintclient.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.joml.Vector2fc;

/**
 * 26.x's GUI pose, a 2D {@link Matrix3x2fStack}, over Minecraft 1.19.2's {@link PoseStack} (as the 1.21.1 overlay's, with
 * 1.19.2's own matrix read through {@link MatrixBridge}). Every change is made to the PoseStack, which is what 1.19.2 draws
 * with; this matrix mirrors its 2D part so it can be read. Pushes and pops are the PoseStack's own, so they nest with
 * Minecraft's.
 */
public final class Pose2D extends Matrix3x2fStack {
	private final PoseStack stack;
	private final Matrix4f scratch = new Matrix4f();
	private final com.mojang.math.Matrix4f product = new com.mojang.math.Matrix4f();

	public Pose2D(PoseStack stack) {
		super(1);
		this.stack = stack;
		sync();
	}

	private static MatrixBridge bridge(com.mojang.math.Matrix4f m) {
		return (MatrixBridge) (Object) m;
	}

	/** Reads the PoseStack's current 2D part (Minecraft may have moved it since the last call). */
	public Pose2D sync() {
		Matrix4f m = bridge(stack.last().pose()).flint$get(scratch);
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
		stack.translate(x, y, 0.0);
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
		stack.mulPose(Vector3f.ZP.rotation(radians));
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
		scratch.set(m.m00(), m.m01(), 0, 0, m.m10(), m.m11(), 0, 0, 0, 0, 1, 0, m.m20(), m.m21(), 0, 1);
		bridge(product).flint$set(scratch);
		stack.last().pose().multiply(product);
		return sync();
	}

	/** Makes the current pose's 2D part {@code m} (its depth part is kept). */
	@Override
	public Matrix3x2f set(Matrix3x2fc m) {
		bridge(stack.last().pose()).flint$set2d(m.m00(), m.m01(), m.m10(), m.m11(), m.m20(), m.m21());
		return sync();
	}

	@Override
	public Matrix3x2f identity() {
		return set(new Matrix3x2f());
	}
}
