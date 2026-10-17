package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tab Editor and Nick Hider in the player list: header, footer, heads, ping as a number, and hidden names. */
@Mixin(PlayerTabOverlay.class)
abstract class PlayerTabOverlayMixin {
	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;header:Lnet/minecraft/network/chat/Component;"))
	private @Nullable Component flint$header(@Nullable Component header) {
		return Mc263Overlay.tabHideHeader() ? null : header;
	}

	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;footer:Lnet/minecraft/network/chat/Component;"))
	private @Nullable Component flint$footer(@Nullable Component footer) {
		return Mc263Overlay.tabHideFooter() ? null : footer;
	}

	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;onlineMode()Z"))
	private boolean flint$heads(boolean showHead) {
		return showHead && !Mc263Overlay.tabHideHeads();
	}

	@Inject(method = "extractPingIcon", at = @At("HEAD"), cancellable = true)
	private void flint$pingNumber(GuiGraphicsExtractor graphics, int slotWidth, int xo, int yo, PlayerInfo info, CallbackInfo callback) {
		if (Mc263Overlay.tabPing(graphics, slotWidth, xo, yo, info)) {
			callback.cancel();
		}
	}

	@ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"))
	private Component flint$name(Component name, PlayerInfo info) {
		return Mc263Overlay.tabName(info, name);
	}

	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
	private void flint$panels(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color,
		com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
		original.call(graphics, x0, y0, x1, y1, color == Integer.MIN_VALUE ? com.flintclient.client.control.platform.Mc263Overlay.tabBackground(color)
			: color);
	}

	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getBackgroundColor(I)I"))
	private int flint$rows(int color) {
		return com.flintclient.client.control.platform.Mc263Overlay.tabRow(color);
	}

	@org.spongepowered.asm.mixin.injection.ModifyConstant(method = "getPlayerInfos", constant = @org.spongepowered.asm.mixin.injection.Constant(longValue = 80L))
	private long flint$limit(long limit) {
		return com.flintclient.client.control.platform.Mc263Overlay.tabLimit(limit);
	}

	@ModifyReturnValue(method = "getPlayerInfos", at = @At("RETURN"))
	private java.util.List<net.minecraft.client.multiplayer.PlayerInfo> flint$order(java.util.List<net.minecraft.client.multiplayer.PlayerInfo> list) {
		return com.flintclient.client.control.platform.Mc263Overlay.tabOrder(list);
	}
}
