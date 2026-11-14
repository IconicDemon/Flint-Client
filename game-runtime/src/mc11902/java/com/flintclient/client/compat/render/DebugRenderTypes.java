package com.flintclient.client.compat.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * The see-through coloured render types 1.20 added and Flint's world drawing writes into ({@code RenderType.debugQuads()} and
 * {@code debugFilledBox()}), made the way 1.20 makes them for Minecraft 1.19.2, which has neither: position and colour,
 * translucent, both faces, depth tested and written; the filled box (a triangle strip) is pulled toward the camera as lines are.
 * 1.19.2 makes its own render types with a package-private factory, so these are set up and cleared shard by shard, as its
 * composite render types do.
 */
public final class DebugRenderTypes extends RenderType {
	private static final RenderType DEBUG_QUADS = new DebugRenderTypes("flint_debug_quads", VertexFormat.Mode.QUADS,
		new RenderStateShard[] {POSITION_COLOR_SHADER, TRANSLUCENT_TRANSPARENCY, LEQUAL_DEPTH_TEST, NO_CULL, COLOR_DEPTH_WRITE});
	private static final RenderType DEBUG_FILLED_BOX = new DebugRenderTypes("flint_debug_filled_box", VertexFormat.Mode.TRIANGLE_STRIP,
		new RenderStateShard[] {POSITION_COLOR_SHADER, TRANSLUCENT_TRANSPARENCY, LEQUAL_DEPTH_TEST, NO_CULL, VIEW_OFFSET_Z_LAYERING, COLOR_DEPTH_WRITE});

	private DebugRenderTypes(String name, VertexFormat.Mode mode, RenderStateShard[] shards) {
		super(name, DefaultVertexFormat.POSITION_COLOR, mode, 256, false, true, () -> {
			for (RenderStateShard shard : shards) {
				shard.setupRenderState();
			}
		}, () -> {
			for (RenderStateShard shard : shards) {
				shard.clearRenderState();
			}
		});
	}

	/** 1.20's {@code RenderType.debugQuads()}: see-through coloured quads. */
	public static RenderType debugQuads() {
		return DEBUG_QUADS;
	}

	/** 1.20's {@code RenderType.debugFilledBox()}: a see-through coloured triangle strip. */
	public static RenderType debugFilledBox() {
		return DEBUG_FILLED_BOX;
	}
}
