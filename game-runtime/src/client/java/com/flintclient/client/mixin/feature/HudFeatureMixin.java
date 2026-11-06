package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263Features;
import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The HUD-side modules on Minecraft's own HUD: the Crosshair and Attack Indicator (drawn where and when vanilla draws
 * its own, so first person, spectator and the 3D-crosshair debug rules all still apply), Titles (hide, print to chat),
 * Scoreboard (hide, numbers, background, shadow) and Boss Bar (hide).
 */
@Mixin(targets = McCompat.HUD_CLASS)
abstract class HudFeatureMixin implements com.flintclient.client.control.platform.Mc263OriginalHud.ActionBar {
	@org.spongepowered.asm.mixin.Shadow
	private int overlayMessageTime;

	@Override
	public boolean flint$actionBarShowing() {
		return overlayMessageTime > 0;
	}

	// ---------- hotbar ----------

	/** Inventory: the hotbar's slot decorations and each slot's key on the slot, after the hotbar is drawn; Loot Beams' tooltip. */
	@Inject(method = "extractItemHotbar", at = @At("TAIL"))
	private void flint$hotbarKeys(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263ArmorHud.draw(graphics, delta);
		com.flintclient.client.control.platform.Mc263Inventory.hotbarDecorations(graphics);
		com.flintclient.client.control.platform.Mc263Inventory.hotbarKeys(graphics);
		com.flintclient.client.control.platform.Mc263Tools.hotbar(graphics);
		com.flintclient.client.control.platform.Mc263LootBeams.hud(graphics);
	}

	/** Armor: the held items' durability over their hotbar slots, after the hotbar and its bars. */
	@Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
	private void flint$armorHands(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263ArmorHud.drawHands(graphics);
	}

	/**
	 * Minecraft's effect icons hidden while Flint draws its own (Effects' tiles): its method returns at once, inside, so the call
	 * itself still happens and the HUD layers other mods attach around it (Simple Voice Chat's group icons) are drawn. Else
	 * Armor's Push: the icons move down below its strip.
	 */
	@Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
	private void flint$effectsPushBegin(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
		if (com.flintclient.client.control.ControlCenter.hideVanillaEffects()) {
			callback.cancel();
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(0.0F, com.flintclient.client.control.platform.Mc263ArmorHud.effectsPush(graphics));
		flint$beneficialIcons = 0;
		flint$harmfulIcons = 0;
	}

	/** Effects' Status Effect Bars on Minecraft's icons: how many of each kind are drawn so far (as Minecraft counts them to place them). */
	@org.spongepowered.asm.mixin.Unique
	private int flint$beneficialIcons;
	@org.spongepowered.asm.mixin.Unique
	private int flint$harmfulIcons;

	/** The effect whose icon comes next. */
	@WrapOperation(method = "extractEffects", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/effect/MobEffectInstance;getEffect()Lnet/minecraft/core/Holder;"))
	private net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> flint$barEffect(net.minecraft.world.effect.MobEffectInstance instance,
		Operation<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> original) {
		com.flintclient.client.control.platform.Mc263EffectBars.drawing(instance, true);
		return original.call(instance);
	}

	/** The bar over the icon's frame, before the icon itself (where Minecraft places that icon: by kind, from the right). */
	@Inject(method = "extractEffects", at = @At(value = "INVOKE",
		target = "L" + McCompat.HUD + ";getMobEffectSprite(Lnet/minecraft/core/Holder;)Lnet/minecraft/resources/Identifier;"))
	private void flint$bar(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
		net.minecraft.world.effect.MobEffectInstance effect = com.flintclient.client.control.platform.Mc263EffectBars.drawn(true);
		if (effect == null) {
			return;
		}
		int x = graphics.guiWidth();
		int y = 1;
		if (net.minecraft.client.Minecraft.getInstance().isDemo()) {
			y += 15;
		}
		if (effect.getEffect().value().isBeneficial()) {
			x -= 25 * ++flint$beneficialIcons;
		} else {
			x -= 25 * ++flint$harmfulIcons;
			y += 26;
		}
		com.flintclient.client.control.platform.Mc263EffectBars.render(graphics, effect, x, y, 24, 24, true);
	}

	@Inject(method = "extractEffects", at = @At("RETURN"))
	private void flint$effectsPushEnd(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
		graphics.pose().popMatrix();
	}

	/** Saturation: saturation and food gain over the hunger bar. */
	@Inject(method = "extractFood", at = @At("TAIL"))
	private void flint$food(GuiGraphicsExtractor graphics, net.minecraft.world.entity.player.Player player, int yLineBase, int xRight,
		CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263Food.foodBar(graphics, player, yLineBase, xRight);
	}

	/** Saturation: the health held food would give back, on the hearts. */
	@Inject(method = "extractHearts", at = @At("TAIL"))
	private void flint$healthGain(GuiGraphicsExtractor graphics, net.minecraft.world.entity.player.Player player, int x, int y, int rowHeight,
		int regenIndex, float maxHealth, int health, int displayHealth, int absorption, boolean blink, CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263Food.hearts(graphics, player, x, y, rowHeight, maxHealth);
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

	// ---------- crosshair (whether vanilla draws its own: HudCrosshairMixin) ----------

	/** Crosshair (Custom Crosshair Mod): Minecraft's whole crosshair is left out; Flint's is drawn over the GUI (CrosshairLayerMixin). */
	@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
	private void flint$customCrosshair(CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263Crosshair.replacesVanilla()) {
			callback.cancel();
		}
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

	/** Title Tweaks on the title (drawn at 4×): its scale, vertical position, opacity and shadow. */
	@WrapOperation(method = "extractTitle", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"))
	private void flint$title(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color, Operation<Void> original) {
		flint$tweakedTitle(graphics, font, text, x, y, width, color, original, false);
	}

	/** Title Tweaks on the subtitle (drawn at 2×). */
	@WrapOperation(method = "extractTitle", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"))
	private void flint$subtitle(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color,
		Operation<Void> original) {
		flint$tweakedTitle(graphics, font, text, x, y, width, color, original, true);
	}

	@org.spongepowered.asm.mixin.Unique
	private static void flint$tweakedTitle(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color,
		Operation<Void> original, boolean subtitle) {
		if (!Mc263Features.tweakTitles()) {
			original.call(graphics, font, text, x, y, width, color);
			return;
		}
		float scale = Mc263Features.titleScale(font, text, subtitle, graphics.guiWidth());
		int ink = Mc263Features.titleColor(color);
		int at = y - Mc263Features.titleOffset(subtitle);
		graphics.pose().pushMatrix();
		graphics.pose().scale(scale, scale);
		if (Mc263Features.titleNoShadow()) {
			graphics.text(font, text, x, at, ink, false);
		} else {
			original.call(graphics, font, text, x, at, width, ink);
		}
		graphics.pose().popMatrix();
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

	// Custom F3 (BetterF3) can hide the sidebar while the debug screen is open; first, so nothing else starts on a hidden one.
	@Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
	private void flint$hideSidebarForF3(GuiGraphicsExtractor graphics, net.minecraft.world.scores.Objective objective, CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263BetterF3.hideSidebar()) {
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

	// ---------- zoom (Zoomify) ----------

	/** Zoom: whether the spyglass overlay shows follows the zoom's spyglass overlay rule. */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "extractCameraOverlays", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;isScoping()Z"))
	private boolean flint$spyglassOverlay(boolean scoping) {
		return com.flintclient.client.control.platform.Mc263Zoom.spyglassOverlay(scoping);
	}

	/** Zoom: the secondary zoom hides the whole HUD while it zooms, as F1 does. */
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void flint$secondaryZoomHud(CallbackInfo callback) {
		if (!com.flintclient.client.control.platform.Mc263Zoom.hudShown()) {
			callback.cancel();
		}
	}
}
