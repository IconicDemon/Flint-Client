package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.CustomPart;
import com.flintclient.client.control.platform.PartDrawing;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 3D Skins and Wavy Capes: a model part with a drawing of Flint's draws it in place of its boxes. Drawn here, at the
 * part, rather than as boxes: Sodium replaces how boxes are drawn (from their sizes alone), not how parts are.
 */
@Mixin(ModelPart.class)
abstract class ModelPartDrawingMixin implements CustomPart {
	@Shadow
	public boolean visible;
	@Shadow
	public boolean skipDraw;
	@Shadow
	@Final
	private Map<String, ModelPart> children;
	@Unique
	private @Nullable PartDrawing flint$drawing;

	@Shadow
	public abstract void translateAndRotate(PoseStack poseStack);

	@Override
	public void flint$drawing(@Nullable PartDrawing drawing) {
		flint$drawing = drawing;
	}

	@Override
	public @Nullable PartDrawing flint$drawing() {
		return flint$drawing;
	}

	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", at = @At("HEAD"),
		cancellable = true)
	private void flint$draw(PoseStack poseStack, VertexConsumer buffer, int light, int overlay, int color, CallbackInfo callback) {
		PartDrawing drawing = flint$drawing;
		if (drawing == null || !visible) {
			return;
		}
		callback.cancel();
		poseStack.pushPose();
		translateAndRotate(poseStack);
		if (!skipDraw) {
			drawing.draw(poseStack.last(), buffer, light, overlay, color);
		}
		for (ModelPart child : children.values()) {
			child.render(poseStack, buffer, light, overlay, color);
		}
		poseStack.popPose();
	}
}
