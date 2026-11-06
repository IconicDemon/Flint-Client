package com.flintclient.client.control.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** What a {@link CustomPart} draws instead of its boxes, in the part's own space, lit and tinted as the boxes would be. */
@FunctionalInterface
public interface PartDrawing {
	void draw(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color);
}
