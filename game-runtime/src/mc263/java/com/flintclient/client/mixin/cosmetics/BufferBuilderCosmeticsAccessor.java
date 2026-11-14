package com.flintclient.client.mixin.cosmetics;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A batched buffer's state, so the 3D cosmetics write a run of vertices in one go ({@code cosmetics/platform/EntityVertices}),
 * as Sodium's vertex writer does: the same bytes {@code addVertex} would write, without a call per vertex.
 */
@Mixin(BufferBuilder.class)
public interface BufferBuilderCosmeticsAccessor {
	@Accessor("buffer")
	ByteBufferBuilder flint$buffer();

	@Accessor("vertices")
	int flint$vertices();

	@Accessor("vertices")
	void flint$setVertices(int vertices);

	@Accessor("vertexPointer")
	void flint$setVertexPointer(long pointer);

	@Accessor("elementsToFill")
	int flint$elementsToFill();

	@Accessor("building")
	boolean flint$building();

	@Accessor("entityFormat")
	boolean flint$entityFormat();

	@Accessor("primitiveTopology")
	PrimitiveTopology flint$topology();
}
