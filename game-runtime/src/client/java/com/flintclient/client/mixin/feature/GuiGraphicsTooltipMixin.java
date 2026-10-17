package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Tooltips;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.joml.Matrix3x2fStack;
import org.joml.Vector2ic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scrollable Tooltips: every screen tooltip is placed with its scroll offset and drawn at its scale. */
@Mixin(GuiGraphicsExtractor.class)
abstract class GuiGraphicsTooltipMixin {
	@Shadow
	@Final
	private Matrix3x2fStack pose;

	@WrapOperation(method = "tooltip", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;positionTooltip(IIIIII)Lorg/joml/Vector2ic;"))
	private Vector2ic flint$place(ClientTooltipPositioner positioner, int screenW, int screenH, int x, int y, int w, int h,
		Operation<Vector2ic> original, @Local(argsOnly = true) List<ClientTooltipComponent> lines) {
		Vector2ic placed = Mc263Tooltips.place((sw, sh, mx, my, tw, th) -> original.call(positioner, sw, sh, mx, my, tw, th), screenW, screenH, x,
			y, w, h, lines.size());
		return placed != null ? placed : original.call(positioner, screenW, screenH, x, y, w, h);
	}

	@Inject(method = "tooltip", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;pushMatrix()Lorg/joml/Matrix3x2fStack;",
		shift = At.Shift.AFTER))
	private void flint$scale(CallbackInfo callback) {
		Mc263Tooltips.transform(pose);
	}
}
