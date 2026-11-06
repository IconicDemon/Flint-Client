package com.flintclient.client.compat.state;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * An item about to be drawn and the model it is drawn with (1.21.4's {@code ItemStackRenderState}): on 1.21.1 a stack, its
 * baked model and the display context it is drawn in. The model's box is what the drawn item fills, as 1.21.4 measures it:
 * the model's own box (a full cube for a 3D model, one pixel deep for a flat item, both centred as ItemRenderer centres
 * them) moved, turned and sized by the context's display transform.
 */
public final class ItemStackRenderState {
	private static final float FLAT_HALF_DEPTH = 0.5F / 16.0F;

	public ItemStack stack = ItemStack.EMPTY;
	public @Nullable BakedModel model;
	public ItemDisplayContext context = ItemDisplayContext.GROUND;

	/** Scratch for the box (render thread only). */
	private final PoseStack transform = new PoseStack();
	private final Vector3f corner = new Vector3f();

	public boolean isEmpty() {
		return stack.isEmpty() || model == null;
	}

	/** The drawn item's box: its model's box under the display transform of {@link #context}. */
	public AABB getModelBoundingBox() {
		BakedModel m = model;
		float halfDepth = m != null && m.isGui3d() ? 0.5F : FLAT_HALF_DEPTH;
		if (m == null) {
			return new AABB(-0.5, -0.5, -halfDepth, 0.5, 0.5, halfDepth);
		}
		transform.setIdentity();
		m.getTransforms().getTransform(context).apply(false, transform);
		Matrix4f matrix = transform.last().pose();
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			matrix.transformPosition((i & 1) == 0 ? -0.5F : 0.5F, (i & 2) == 0 ? -0.5F : 0.5F, (i & 4) == 0 ? -halfDepth : halfDepth, corner);
			minX = Math.min(minX, corner.x);
			minY = Math.min(minY, corner.y);
			minZ = Math.min(minZ, corner.z);
			maxX = Math.max(maxX, corner.x);
			maxY = Math.max(maxY, corner.y);
			maxZ = Math.max(maxZ, corner.z);
		}
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	public void clear() {
		stack = ItemStack.EMPTY;
		model = null;
		context = ItemDisplayContext.GROUND;
	}
}
