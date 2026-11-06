/*
 * Adapted from Model Gap Fix by MehVahdJukaar (https://github.com/MehVahdJukaar/modelfix-multi), GPL-3.0: the licence text is
 * in META-INF/licenses/GPL-3.0-ModelGapFix.txt. This file is covered by the GPL-3.0.
 */
package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ModelFix;
import com.flintclient.client.control.platform.ModelGapSpans;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Model Gap Fix on 1.21.1 (as on 1.21.10; later versions build item models without gaps), where the generator's side and
 * span methods belong to its instance and an element keeps its corners and faces in fields: a generated item's side quads moved toward
 * its centre (and enlarged, if set), and with an expansion each run of edge pixels gets its own quad.
 */
@Mixin(ItemModelGenerator.class)
abstract class ModelGapFixMixin {
	@ModifyReturnValue(method = "createSideElements", at = @At("RETURN"))
	private List<BlockElement> flint$sides(List<BlockElement> elements) {
		Mc263ModelFix.Params p = Mc263ModelFix.baking();
		if (!p.on()) {
			return elements;
		}
		float inc = (float) p.indent();
		float inc2 = (float) p.expansion();
		for (BlockElement e : elements) {
			Map<Direction, BlockElementFace> faces = e.faces;
			if (faces.size() != 1 || !(e.from instanceof Vector3f from) || !(e.to instanceof Vector3f to)) {
				continue;
			}
			switch (faces.keySet().iterator().next()) {
				case UP -> {
					from.set(from.x() - inc2, from.y() - inc, from.z() - inc2);
					to.set(to.x() + inc2, to.y() - inc, to.z() + inc2);
				}
				case DOWN -> {
					from.set(from.x() - inc2, from.y() + inc, from.z() - inc2);
					to.set(to.x() + inc2, to.y() + inc, to.z() + inc2);
				}
				case WEST -> {
					from.set(from.x() - inc, from.y() + inc2, from.z() - inc2);
					to.set(to.x() - inc, to.y() - inc2, to.z() + inc2);
				}
				case EAST -> {
					from.set(from.x() + inc, from.y() + inc2, from.z() - inc2);
					to.set(to.x() + inc, to.y() - inc2, to.z() + inc2);
				}
				default -> {
				}
			}
		}
		return elements;
	}

	/** With an expansion, a span only grows over the next pixel: separate runs stay separate quads. */
	@Inject(method = "createOrExpandSpan", at = @At("HEAD"), cancellable = true)
	private void flint$span(List<Object> spans, @Coerce Object facing, int pixelX, int pixelY, CallbackInfo callback) {
		Mc263ModelFix.Params p = Mc263ModelFix.baking();
		if (!p.on() || p.expansion() == 0) {
			return;
		}
		try {
			boolean horizontal = (boolean) ModelGapSpans.IS_HORIZONTAL.invoke(facing);
			int anchor = horizontal ? pixelY : pixelX;
			int along = horizontal ? pixelX : pixelY;
			Object existing = null;
			for (Object span : spans) {
				if (ModelGapSpans.FACING.invoke(span) == facing && (int) ModelGapSpans.ANCHOR.invoke(span) == anchor && (int) ModelGapSpans.MAX.invoke(span) == along - 1) {
					existing = span;
					break;
				}
			}
			if (existing == null) {
				spans.add(ModelGapSpans.NEW.invoke(facing, along, anchor));
			} else {
				ModelGapSpans.EXPAND.invoke(existing, along);
			}
			callback.cancel();
		} catch (Throwable e) {
			// Minecraft's own merge then.
		}
	}
}
