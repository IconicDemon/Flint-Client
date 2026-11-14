package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.MatrixBridge;
import com.mojang.math.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Minecraft 1.19.2: its matrix read and written as JOML's ({@link MatrixBridge}). */
@Mixin(Matrix4f.class)
abstract class MatrixBridgeMixin implements MatrixBridge {
	@Shadow
	protected float m00;
	@Shadow
	protected float m01;
	@Shadow
	protected float m02;
	@Shadow
	protected float m03;
	@Shadow
	protected float m10;
	@Shadow
	protected float m11;
	@Shadow
	protected float m12;
	@Shadow
	protected float m13;
	@Shadow
	protected float m20;
	@Shadow
	protected float m21;
	@Shadow
	protected float m22;
	@Shadow
	protected float m23;
	@Shadow
	protected float m30;
	@Shadow
	protected float m31;
	@Shadow
	protected float m32;
	@Shadow
	protected float m33;

	@Override
	public org.joml.Matrix4f flint$get(org.joml.Matrix4f out) {
		return out.set(m00, m10, m20, m30, m01, m11, m21, m31, m02, m12, m22, m32, m03, m13, m23, m33);
	}

	@Override
	public void flint$set(Matrix4fc m) {
		m00 = m.m00();
		m10 = m.m01();
		m20 = m.m02();
		m30 = m.m03();
		m01 = m.m10();
		m11 = m.m11();
		m21 = m.m12();
		m31 = m.m13();
		m02 = m.m20();
		m12 = m.m21();
		m22 = m.m22();
		m32 = m.m23();
		m03 = m.m30();
		m13 = m.m31();
		m23 = m.m32();
		m33 = m.m33();
	}

	@Override
	public void flint$set2d(float a00, float a01, float a10, float a11, float a30, float a31) {
		m00 = a00;
		m10 = a01;
		m01 = a10;
		m11 = a11;
		m03 = a30;
		m13 = a31;
	}
}
