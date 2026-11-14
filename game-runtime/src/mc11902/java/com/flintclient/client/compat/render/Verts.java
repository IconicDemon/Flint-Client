package com.flintclient.client.compat.render;

import com.flintclient.client.compat.MatrixBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * 1.21's vertex and pose calls on Minecraft 1.19.2 (whose poses hold its own matrices and whose vertices end with endVertex): the
 * pose's matrix as JOML's, a normal turned by the pose, and the one-call vertex. Render thread only (one scratch vector).
 */
public final class Verts {
	private static final com.mojang.math.Vector3f NORMAL = new com.mojang.math.Vector3f();

	private Verts() {
	}

	/** The pose's matrix as JOML's (a copy: changing it changes nothing in the pose). */
	public static Matrix4f joml(PoseStack.Pose pose) {
		return ((MatrixBridge) (Object) pose.pose()).flint$get(new Matrix4f());
	}

	/** {@code normal} turned by the pose's normal matrix into {@code out}, made unit length (as 1.21's transformNormal). */
	public static Vector3f transformNormal(PoseStack.Pose pose, Vector3fc normal, Vector3f out) {
		NORMAL.set(normal.x(), normal.y(), normal.z());
		NORMAL.transform(pose.normal());
		out.set(NORMAL.x(), NORMAL.y(), NORMAL.z());
		float length = out.lengthSquared();
		if (length > 0.0F && Math.abs(length - 1.0F) > 1.0E-6F) {
			out.normalize();
		}
		return out;
	}

	/** One whole vertex (1.21's addVertex with every element; the colour 0xAARRGGBB). */
	public static void vertex(VertexConsumer buffer, float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny,
		float nz) {
		buffer.vertex(x, y, z, (color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, (color >>> 24) / 255.0F, u, v, overlay,
			light, nx, ny, nz);
	}
}
