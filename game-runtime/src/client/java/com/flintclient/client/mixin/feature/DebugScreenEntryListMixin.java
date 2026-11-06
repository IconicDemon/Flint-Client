package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263BetterF3;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Custom F3 (BetterF3): opening and closing the debug screen goes through its animation (closing waits for the columns to
 * slide out), and the 3D crosshair can be hidden while it's open.
 */
@Mixin(DebugScreenEntryList.class)
abstract class DebugScreenEntryListMixin implements Mc263BetterF3.Entries {
	@Shadow
	private boolean isOverlayVisible;

	@Shadow
	public abstract void rebuildCurrentList();

	@Override
	public void flint$setOverlay(boolean visible) {
		isOverlayVisible = visible;
		rebuildCurrentList();
	}

	@Inject(method = "setOverlayVisible", at = @At("HEAD"), cancellable = true)
	private void flint$animate(boolean visible, CallbackInfo callback) {
		if (Mc263BetterF3.setOverlay(this, isOverlayVisible, visible)) {
			callback.cancel();
		}
	}

	@Inject(method = "isCurrentlyEnabled", at = @At("HEAD"), cancellable = true)
	private void flint$hideCrosshair(Identifier id, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263BetterF3.hidesEntry(id)) {
			callback.setReturnValue(false);
		}
	}
}
