package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.mixin.cosmetics.BufferBuilderCosmeticsAccessor;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/**
 * Writes the 3D cosmetics' vertices straight into Minecraft's batched entity buffer, a whole run at once: room for it is
 * taken in one step and every vertex's 36 bytes are stored as {@code BufferBuilder.addVertex} stores them for Minecraft's
 * entity format (position, colour, uv, overlay, light, normal), with the buffer left as after that many {@code addVertex}
 * calls. Only for a plain buffer in that exact format (a shader pack's own entity format, an outline or other wrapping
 * buffer, a big-endian machine: {@link #reserve} answers 0 and the caller writes through {@code addVertex} as before).
 * Fault-isolated: a failure turns this off for the session.
 */
final class EntityVertices {
	/** Bytes per vertex of Minecraft's entity format. */
	static final int SIZE = 36;
	private static final int MAX_VERTICES = 16777215;
	private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
	static boolean enabled = LITTLE_ENDIAN && !"false".equals(System.getProperty("flint.cosmetics.directWrite"));

	private EntityVertices() {
	}

	/**
	 * Room for {@code count} vertices in {@code buffer}: the address of the first (each {@link #SIZE} bytes on), counted as
	 * written; 0 when this buffer can't be written so (then nothing changed).
	 */
	static long reserve(VertexConsumer buffer, int count) {
		if (!enabled || count <= 0 || !(buffer instanceof BufferBuilder)) {
			return 0;
		}
		try {
			BufferBuilderCosmeticsAccessor b = (BufferBuilderCosmeticsAccessor) buffer;
			int vertices = b.flint$vertices();
			// As addVertex begins a vertex: building, the last vertex complete, room left; quads only (lines double vertices).
			if (!b.flint$building() || !b.flint$entityFormat() || b.flint$topology() != PrimitiveTopology.QUADS
				|| vertices != 0 && b.flint$elementsToFill() != 0 || vertices > MAX_VERTICES - count) {
				return 0;
			}
			long at = b.flint$buffer().reserve(count * SIZE);
			b.flint$setVertices(vertices + count);
			b.flint$setVertexPointer(at + (long) (count - 1) * SIZE);
			return at;
		} catch (RuntimeException | LinkageError e) {
			enabled = false;
			FlintClient.LOGGER.warn("Flint 3D cosmetics: writing vertices directly failed; using addVertex for this session", e);
			return 0;
		}
	}

	/** One vertex at {@code at}, as {@code addVertex(x, y, z, color, u, v, overlay, light, normal)} writes it. */
	static void put(long at, float x, float y, float z, int abgr, float u, float v, int overlay, int light, int normal) {
		MemoryUtil.memPutFloat(at, x);
		MemoryUtil.memPutFloat(at + 4, y);
		MemoryUtil.memPutFloat(at + 8, z);
		MemoryUtil.memPutInt(at + 12, abgr);
		MemoryUtil.memPutFloat(at + 16, u);
		MemoryUtil.memPutFloat(at + 20, v);
		MemoryUtil.memPutInt(at + 24, overlay);
		MemoryUtil.memPutInt(at + 28, light);
		MemoryUtil.memPutInt(at + 32, normal);
	}

	/** A normal's three bytes as Minecraft packs them (the fourth, padding, 0). */
	static int normal(float nx, float ny, float nz) {
		return (byte1(nx) & 0xFF) | (byte1(ny) & 0xFF) << 8 | (byte1(nz) & 0xFF) << 16;
	}

	private static int byte1(float f) {
		// BufferBuilder.normalIntValue: (byte) ((int) (clamp(f, -1, 1) * 127) & 0xFF).
		return (int) (Math.max(-1F, Math.min(1F, f)) * 127.0F) & 0xFF;
	}

	/** A colour as Minecraft stores it (ARGB to ABGR, little-endian). */
	static int abgr(int argb) {
		return argb & 0xFF00FF00 | (argb & 0xFF) << 16 | argb >> 16 & 0xFF;
	}
}
