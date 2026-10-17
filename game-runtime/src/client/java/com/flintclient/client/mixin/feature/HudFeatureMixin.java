package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The HUD-side modules on Minecraft's own HUD: the Crosshair and Attack Indicator (drawn where and when vanilla draws
 * its own, so first person, spectator and the 3D-crosshair debug rules all still apply), Titles (hide, print to chat),
 * Scoreboard (hide, numbers, background, shadow) and Boss Bar (hide).
 */
@Mixin(Hud.class)
abstract class HudFeatureMixin {
	// ---------- hotbar ----------

	/** Inventory: each hotbar slot's key on the slot, after the hotbar is drawn. */
	@Inject(method = "extractItemHotbar", at = @At("TAIL"))
	private void flint$hotbarKeys(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263Inventory.hotbarKeys(graphics);
	}

	/** Saturation: saturation and food gain over the hunger bar. */
	@Inject(method = "extractFood", at = @At("TAIL"))
	private void flint$food(GuiGraphicsExtractor graphics, net.minecraft.world.entity.player.Player player, int yLineBase, int xRight,
		CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263Food.foodBar(graphics, player, yLineBase, xRight);
	}

	/** Chat: hidden from the HUD by its toggle key. */
	@Inject(method = "extractChat", at = @At("HEAD"), cancellable = true)
	private void flint$chat(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263Chat.hideOnHud()) {
			callback.cancel();
		}
	}

	/** Tab Editor's Toggle tablist: a tap shows the list until the next tap. */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "extractTabList", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
	private boolean flint$tabKey(boolean held) {
		return Mc263Overlay.tabShown(held);
	}

	// ---------- crosshair ----------

	@WrapWithCondition(method = "extractCrosshair", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private boolean flint$crosshair(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
		return !Mc263Overlay.crosshair(graphics);
	}

	@WrapWithCondition(method = "extractCrosshair", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private boolean flint$vanillaIndicatorFull(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
		return !Mc263Overlay.ownAttackIndicator();
	}

	@WrapWithCondition(method = "extractCrosshair", at = @At(value = "INVOKE", ordinal = 2,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private boolean flint$vanillaIndicatorBack(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
		return !Mc263Overlay.ownAttackIndicator();
	}

	@WrapWithCondition(method = "extractCrosshair", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V"))
	private boolean flint$vanillaIndicatorProgress(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int sw, int sh, int u,
		int v, int x, int y, int w, int h) {
		return !Mc263Overlay.ownAttackIndicator();
	}

	@Inject(method = "extractCrosshair", at = @At("TAIL"))
	private void flint$afterCrosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
		Mc263Overlay.afterCrosshair(graphics);
	}

	// ---------- titles ----------

	@Inject(method = "extractTitle", at = @At("HEAD"), cancellable = true)
	private void flint$hideTitles(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
		if (!Mc263Features.showTitles()) {
			callback.cancel();
		}
	}

	@Inject(method = "setTitle", at = @At("HEAD"))
	private void flint$title(Component title, CallbackInfo callback) {
		Mc263Features.title(title, false);
	}

	@Inject(method = "setSubtitle", at = @At("HEAD"))
	private void flint$subtitle(Component subtitle, CallbackInfo callback) {
		Mc263Features.title(subtitle, true);
	}

	@Inject(method = "setOverlayMessage", at = @At("HEAD"))
	private void flint$actionbar(Component text, boolean animate, CallbackInfo callback) {
		// "Now playing" (animated) is the game's own, not the server's action bar.
		if (!animate) {
			Mc263Features.actionbar(text);
		}
	}

	// ---------- scoreboard ----------

	@Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
	private void flint$hideScoreboard(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
		if (Mc263Overlay.hideScoreboard()) {
			callback.cancel();
		}
	}

	@Inject(method = "displayScoreboardSidebar", at = @At("HEAD"))
	private void flint$scoreboardBegin(GuiGraphicsExtractor graphics, net.minecraft.world.scores.Objective objective, CallbackInfo callback) {
		Mc263Overlay.scoreboardBegin(graphics);
	}

	@Inject(method = "displayScoreboardSidebar", at = @At("RETURN"))
	private void flint$scoreboardEnd(GuiGraphicsExtractor graphics, net.minecraft.world.scores.Objective objective, CallbackInfo callback) {
		Mc263Overlay.scoreboardEnd(graphics);
	}

	@WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
	private void flint$scoreboardTitleFill(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color, Operation<Void> original) {
		Mc263Overlay.scoreboardFill(graphics, x0, y0, x1, y1, color, true);
	}

	@WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
	private void flint$scoreboardFill(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color, Operation<Void> original) {
		Mc263Overlay.scoreboardFill(graphics, x0, y0, x1, y1, color, false);
	}

	@WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/Options;getBackgroundColor(F)I"))
	private int flint$scoreboardBackground(Options options, float opacity, Operation<Integer> original) {
		return original.call(options, Mc263Overlay.scoreboardOpacity(opacity));
	}

	@WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"))
	private void flint$scoreboardTitle(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color, boolean shadow,
		Operation<Void> original) {
		original.call(graphics, font, text, x, y, Mc263Overlay.scoreboardText(color), Mc263Overlay.scoreboardShadow(shadow));
	}

	@WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"))
	private void flint$scoreboardName(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color, boolean shadow,
		Operation<Void> original) {
		original.call(graphics, font, text, x, y, Mc263Overlay.scoreboardText(color), Mc263Overlay.scoreboardShadow(shadow));
	}

	@WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", ordinal = 2,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"))
	private void flint$scoreboardNumber(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color, boolean shadow,
		Operation<Void> original) {
		if (Mc263Overlay.scoreboardNumbers()) {
			original.call(graphics, font, text, x, y, Mc263Overlay.scoreboardText(color), Mc263Overlay.scoreboardShadow(shadow));
		}
	}

	// ---------- boss bars ----------

	@Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
	private void flint$hideBossBars(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
		if (Mc263Overlay.hideBossBars()) {
			callback.cancel();
			return;
		}
		Mc263Overlay.bossBegin(graphics);
	}

	@Inject(method = "extractBossOverlay", at = @At("RETURN"))
	private void flint$bossEnd(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
		Mc263Overlay.bossEnd(graphics);
	}
}
