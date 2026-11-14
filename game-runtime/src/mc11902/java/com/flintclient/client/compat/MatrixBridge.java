package com.flintclient.client.compat;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Minecraft 1.19.2's own 4x4 matrix ({@code com.mojang.math.Matrix4f}, which 1.19.3 swapped for JOML's) read and written as
 * JOML's, which the shared sources compute with. Loom adds this interface to Minecraft's matrix at compile time
 * ({@code src/mc11902/loom/fabric.mod.json}) and {@code mixin.bridge.MatrixBridgeMixin} at run time. Minecraft names a
 * matrix's numbers by row then column (m03 is the x translation), JOML by column then row (m30): both mean the same matrix.
 */
public interface MatrixBridge {
	/** Copies this matrix into {@code out}; returns {@code out}. */
	default Matrix4f flint$get(Matrix4f out) {
		throw new IllegalStateException("MatrixBridgeMixin isn't applied");
	}

	/** Makes this matrix {@code m}. */
	default void flint$set(Matrix4fc m) {
		throw new IllegalStateException("MatrixBridgeMixin isn't applied");
	}

	/** Sets the 2D part (JOML's m00, m01, m10, m11, m30, m31) and keeps the rest. */
	default void flint$set2d(float m00, float m01, float m10, float m11, float m30, float m31) {
		throw new IllegalStateException("MatrixBridgeMixin isn't applied");
	}
}
