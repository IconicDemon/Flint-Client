package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tab Editor and Nick Hider in the player list: header, footer, heads, ping as a number, and hidden names. Minecraft 1.19.2
 * picks the players to list inside render (sorted, then cut to 80), where 1.20.2 and later have getPlayerInfos: the limit
 * and the order are applied there, to the list it draws.
 */
@Mixin(PlayerTabOverlay.class)
abstract class PlayerTabOverlayMixin {
	@ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;header:Lnet/minecraft/network/chat/Component;"))
	private @Nullable Component flint$header(@Nullable Component header) {
		return Mc263Overlay.tabHideHeader() ? null : header;
	}

	@ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;footer:Lnet/minecraft/network/chat/Component;"))
	private @Nullable Component flint$footer(@Nullable Component footer) {
		return Mc263Overlay.tabHideFooter() ? null : footer;
	}

	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;isEncrypted()Z"))
	private boolean flint$heads(boolean showHead) {
		return showHead && !Mc263Overlay.tabHideHeads();
	}

	@Inject(method = "renderPingIcon", at = @At("HEAD"), cancellable = true)
	private void flint$pingNumber(PoseStack pose, int slotWidth, int xo, int yo, PlayerInfo info, CallbackInfo callback) {
		if (Mc263Overlay.tabPing(GuiGraphics.of(pose), slotWidth, xo, yo, info)) {
			callback.cancel();
		}
	}

	/** Tab Editor's ping numbers (Ping View) need a wider column than the bars. */
	@ModifyExpressionValue(method = "render", at = @At(value = "CONSTANT", args = "intValue=13"))
	private int flint$pingWidth(int width) {
		return Mc263Overlay.tabPingWidth(width);
	}

	@ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"))
	private Component flint$name(Component name, PlayerInfo info) {
		return com.flintclient.client.control.platform.Mc263Nametags.tabBadge(info, Mc263Overlay.tabName(info, name));
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;fill(Lcom/mojang/blaze3d/vertex/PoseStack;IIIII)V"))
	private void flint$panels(PoseStack pose, int x0, int y0, int x1, int y1, int color, Operation<Void> original) {
		original.call(pose, x0, y0, x1, y1, color == Integer.MIN_VALUE ? Mc263Overlay.tabBackground(color) : color);
	}

	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getBackgroundColor(I)I"))
	private int flint$rows(int color) {
		return Mc263Overlay.tabRow(color);
	}

	/** How many players are listed (Minecraft cuts the sorted list to 80). */
	@ModifyExpressionValue(method = "render", at = @At(value = "CONSTANT", args = "intValue=80"))
	private int flint$limit(int limit) {
		return (int) Math.min(Integer.MAX_VALUE, Mc263Overlay.tabLimit(limit));
	}

	/** The listed players' order, on the cut list (as 1.20.2's getPlayerInfos returns it). */
	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Ljava/util/List;subList(II)Ljava/util/List;"))
	private List<PlayerInfo> flint$order(List<PlayerInfo> list) {
		return Mc263Overlay.tabOrder(list);
	}
}
