package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263BetterF3;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Collection;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Custom F3 (BetterF3): while the debug screen is open Minecraft gathers none of its own lines and no help lines, the
 * columns are drawn by Flint (scaled, sliding in and out), the screen can open itself with the game, and the charts are
 * reachable for the module's "always enable" switches.
 */
@Mixin(DebugScreenOverlay.class)
abstract class CustomF3Mixin implements Mc263BetterF3.Charts {
	@Shadow
	private boolean renderProfilerChart;
	@Shadow
	private boolean renderFpsCharts;

	@Shadow
	public abstract void toggleNetworkCharts();

	@Override
	public boolean flint$fpsCharts() {
		return renderFpsCharts;
	}

	@Override
	public void flint$showCharts(boolean profiler, boolean fps) {
		renderProfilerChart |= profiler;
		renderFpsCharts |= fps;
	}

	@Override
	public void flint$toggleNetworkCharts() {
		toggleNetworkCharts();
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void flint$begin(GuiGraphicsExtractor graphics, CallbackInfo callback) {
		Mc263BetterF3.begin(graphics);
	}

	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void flint$end(GuiGraphicsExtractor graphics, CallbackInfo callback) {
		Mc263BetterF3.end(graphics);
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/debug/DebugScreenEntryList;getCurrentlyEnabled()Ljava/util/Collection;"))
	private Collection<Identifier> flint$entries(DebugScreenEntryList entries, Operation<Collection<Identifier>> original) {
		return Mc263BetterF3.enabled(original.call(entries));
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/debug/DebugScreenEntryList;isOverlayVisible()Z"))
	private boolean flint$helpLines(DebugScreenEntryList entries, Operation<Boolean> original) {
		return Mc263BetterF3.overlayVisible(original.call(entries));
	}

	@Inject(method = "extractRenderState", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/client/gui/components/debug/DebugScreenEntryList;getCurrentlyEnabled()Ljava/util/Collection;"))
	private void flint$fontScale(GuiGraphicsExtractor graphics, CallbackInfo callback) {
		Mc263BetterF3.scale(graphics);
	}

	@Inject(method = "extractRenderState", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V"))
	private void flint$animate(GuiGraphicsExtractor graphics, CallbackInfo callback) {
		Mc263BetterF3.animate();
	}

	// The column's graphics and side are picked by type: 26.3 adds the width after them, 26.2 and older have none.
	@Inject(method = "extractLines", at = @At("HEAD"), cancellable = true)
	private void flint$column(CallbackInfo callback, @Local(argsOnly = true) GuiGraphicsExtractor graphics, @Local(argsOnly = true) boolean left) {
		if (Mc263BetterF3.drawColumn(graphics, left, McCompat.f3Width(graphics))) {
			callback.cancel();
		}
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void flint$autoStart(CallbackInfo callback) {
		Mc263BetterF3.autoStart(this);
	}

	@Inject(method = "reset", at = @At("RETURN"))
	private void flint$autoStartAgain(CallbackInfo callback) {
		Mc263BetterF3.autoStart(this);
	}

	@Inject(method = "showDebugScreen", at = @At("HEAD"), cancellable = true)
	private void flint$shown(CallbackInfoReturnable<Boolean> callback) {
		Boolean shown = Mc263BetterF3.showDebugScreen();
		if (shown != null) {
			callback.setReturnValue(shown);
		}
	}
}
