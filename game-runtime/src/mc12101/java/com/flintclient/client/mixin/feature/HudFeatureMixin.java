package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The HUD-side modules on Minecraft 1.21.1's own HUD: the hotbar's extras, Armor, Status Effect Bars, Saturation, Chat's
 * toggle, the tab list key, Titles (hide, print to chat, tweaks) and Scoreboard (hide, numbers, background, shadow). Boss
 * bars are BossHealthOverlayMixin's (1.21.1 draws them from a lambda). The scoreboard is drawn inside a managed batch whose
 * lambda is {@code method_55440}.
 */
@Mixin(Gui.class)
abstract class HudFeatureMixin implements com.flintclient.client.control.platform.Mc263OriginalHud.ActionBar {
	@Shadow
	private int overlayMessageTime;

	@Override
	public boolean flint$actionBarShowing() {
		return overlayMessageTime > 0;
	}

	// ---------- hotbar ----------

	/** Inventory: the hotbar's slot decorations and each slot's key on the slot, after the hotbar is drawn; Loot Beams' tooltip. */
	@Inject(method = "renderItemHotbar", at = @At("TAIL"))
	private void flint$hotbarKeys(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263ArmorHud.draw(graphics, delta);
		com.flintclient.client.control.platform.Mc263Inventory.hotbarDecorations(graphics);
		com.flintclient.client.control.platform.Mc263Inventory.hotbarKeys(graphics);
		com.flintclient.client.control.platform.Mc263Tools.hotbar(graphics);
		com.flintclient.client.control.platform.Mc263LootBeams.hud(graphics);
	}

	/** Armor: the held items' durability over their hotbar slots, after the hotbar and its bars. */
	@Inject(method = "renderHotbarAndDecorations", at = @At("TAIL"))
	private void flint$armorHands(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263ArmorHud.drawHands(graphics);
	}

	// ---------- effects ----------

	/** Armor's Push effect icons: Minecraft's effect icons move down below its strip. */
	@Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
	private void flint$effectsPushBegin(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
		// Hidden while Flint draws its own: returned from inside, so mods' HUD layers attached around the call still draw.
		if (com.flintclient.client.control.ControlCenter.hideVanillaEffects()) {
			callback.cancel();
			return;
		}
		graphics.pose2d().pushMatrix();
		graphics.pose2d().translate(0.0F, com.flintclient.client.control.platform.Mc263ArmorHud.effectsPush(graphics));
		flint$beneficialIcons = 0;
		flint$harmfulIcons = 0;
	}

	@Unique
	private int flint$beneficialIcons;
	@Unique
	private int flint$harmfulIcons;

	/** The effect whose icon comes next. */
	@WrapOperation(method = "renderEffects", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/effect/MobEffectInstance;getEffect()Lnet/minecraft/core/Holder;"))
	private net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> flint$barEffect(net.minecraft.world.effect.MobEffectInstance instance,
		Operation<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> original) {
		com.flintclient.client.control.platform.Mc263EffectBars.drawing(instance, true);
		return original.call(instance);
	}

	/** The bar over the icon's frame (where Minecraft places that icon: by kind, from the right), as the icon's sprite is fetched. */
	@Inject(method = "renderEffects", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/resources/MobEffectTextureManager;get(Lnet/minecraft/core/Holder;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
	private void flint$bar(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
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

	@Inject(method = "renderEffects", at = @At("RETURN"))
	private void flint$effectsPushEnd(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
		graphics.pose2d().popMatrix();
	}

	// ---------- Saturation ----------

	/** Saturation and food gain over the hunger bar. */
	@Inject(method = "renderFood", at = @At("TAIL"))
	private void flint$food(GuiGraphics graphics, net.minecraft.world.entity.player.Player player, int yLineBase, int xRight,
		CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263Food.foodBar(graphics, player, yLineBase, xRight);
	}

	/** The health held food would give back, on the hearts. */
	@Inject(method = "renderHearts", at = @At("TAIL"))
	private void flint$healthGain(GuiGraphics graphics, net.minecraft.world.entity.player.Player player, int x, int y, int rowHeight,
		int regenIndex, float maxHealth, int health, int displayHealth, int absorption, boolean blink, CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263Food.hearts(graphics, player, x, y, rowHeight, maxHealth);
	}

	// ---------- chat and the tab list ----------

	/** Chat: hidden from the HUD by its toggle key (the chat call only, so Flint's HUD, drawn as the chat layer starts, stays). */
	@WrapWithCondition(method = "renderChat", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V"))
	private boolean flint$chat(ChatComponent chat, GuiGraphics graphics, int tick, int mouseX, int mouseY, boolean focused) {
		return !com.flintclient.client.control.platform.Mc263Chat.hideOnHud();
	}

	/** Tab Editor's Toggle tablist: a tap shows the list until the next tap. */
	@ModifyExpressionValue(method = "renderTabList", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
	private boolean flint$tabKey(boolean held) {
		return Mc263Overlay.tabShown(held);
	}

	// ---------- crosshair (whether vanilla draws its own: HudCrosshairMixin) ----------

	/** Crosshair (Custom Crosshair Mod): Minecraft's whole crosshair is left out; Flint's is drawn over the HUD (CrosshairLayerMixin). */
	@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
	private void flint$customCrosshair(CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263Crosshair.replacesVanilla()) {
			callback.cancel();
		}
	}

	@Inject(method = "renderCrosshair", at = @At("TAIL"))
	private void flint$afterCrosshair(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
		Mc263Overlay.afterCrosshair(graphics);
	}

	// ---------- titles ----------

	@Inject(method = "renderTitle", at = @At("HEAD"), cancellable = true)
	private void flint$hideTitles(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
		if (!Mc263Features.showTitles()) {
			callback.cancel();
		}
	}

	/** Title Tweaks on the title (drawn at 4×): its scale, vertical position, opacity and shadow. */
	@WrapOperation(method = "renderTitle", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I"))
	private int flint$title(GuiGraphics graphics, Font font, Component text, int x, int y, int width, int color, Operation<Integer> original) {
		return flint$tweakedTitle(graphics, font, text, x, y, width, color, original, false);
	}

	/** Title Tweaks on the subtitle (drawn at 2×). */
	@WrapOperation(method = "renderTitle", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)I"))
	private int flint$subtitle(GuiGraphics graphics, Font font, Component text, int x, int y, int width, int color, Operation<Integer> original) {
		return flint$tweakedTitle(graphics, font, text, x, y, width, color, original, true);
	}

	@Unique
	private static int flint$tweakedTitle(GuiGraphics graphics, Font font, Component text, int x, int y, int width, int color,
		Operation<Integer> original, boolean subtitle) {
		if (!Mc263Features.tweakTitles()) {
			return original.call(graphics, font, text, x, y, width, color);
		}
		float scale = Mc263Features.titleScale(font, text, subtitle, graphics.guiWidth());
		int ink = Mc263Features.titleColor(color);
		int at = y - Mc263Features.titleOffset(subtitle);
		graphics.pose2d().pushMatrix();
		graphics.pose2d().scale(scale, scale);
		int end;
		if (Mc263Features.titleNoShadow()) {
			graphics.text(font, text, x, at, ink, false);
			end = x + font.width(text);
		} else {
			end = original.call(graphics, font, text, x, at, width, ink);
		}
		graphics.pose2d().popMatrix();
		return end;
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

	@Inject(method = "renderScoreboardSidebar", at = @At("HEAD"), cancellable = true)
	private void flint$hideScoreboard(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
		if (Mc263Overlay.hideScoreboard()) {
			callback.cancel();
		}
	}

	// Custom F3 (BetterF3) can hide the sidebar while the debug screen is open; first, so nothing else starts on a hidden one.
	@Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
	private void flint$hideSidebarForF3(GuiGraphics graphics, net.minecraft.world.scores.Objective objective, CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263BetterF3.hideSidebar()) {
			callback.cancel();
		}
	}

	@Inject(method = "displayScoreboardSidebar", at = @At("HEAD"))
	private void flint$scoreboardBegin(GuiGraphics graphics, net.minecraft.world.scores.Objective objective, CallbackInfo callback) {
		Mc263Overlay.scoreboardBegin(graphics);
	}

	@Inject(method = "displayScoreboardSidebar", at = @At("RETURN"))
	private void flint$scoreboardEnd(GuiGraphics graphics, net.minecraft.world.scores.Objective objective, CallbackInfo callback) {
		Mc263Overlay.scoreboardEnd(graphics);
	}

	@WrapOperation(method = "method_55440", at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
	private void flint$scoreboardTitleFill(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color, Operation<Void> original) {
		Mc263Overlay.scoreboardFill(graphics, x0, y0, x1, y1, color, true);
	}

	@WrapOperation(method = "method_55440", at = @At(value = "INVOKE", ordinal = 1, target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
	private void flint$scoreboardFill(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color, Operation<Void> original) {
		Mc263Overlay.scoreboardFill(graphics, x0, y0, x1, y1, color, false);
	}

	@WrapOperation(method = "method_55440", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getBackgroundColor(F)I"))
	private int flint$scoreboardBackground(Options options, float opacity, Operation<Integer> original) {
		return original.call(options, Mc263Overlay.scoreboardOpacity(opacity));
	}

	@WrapOperation(method = "method_55440", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"))
	private int flint$scoreboardTitle(GuiGraphics graphics, Font font, Component text, int x, int y, int color, boolean shadow,
		Operation<Integer> original) {
		return original.call(graphics, font, text, x, y, Mc263Overlay.scoreboardText(color), Mc263Overlay.scoreboardShadow(shadow));
	}

	@WrapOperation(method = "method_55440", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"))
	private int flint$scoreboardName(GuiGraphics graphics, Font font, Component text, int x, int y, int color, boolean shadow,
		Operation<Integer> original) {
		return original.call(graphics, font, text, x, y, Mc263Overlay.scoreboardText(color), Mc263Overlay.scoreboardShadow(shadow));
	}

	@WrapOperation(method = "method_55440", at = @At(value = "INVOKE", ordinal = 2,
		target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"))
	private int flint$scoreboardNumber(GuiGraphics graphics, Font font, Component text, int x, int y, int color, boolean shadow,
		Operation<Integer> original) {
		if (Mc263Overlay.scoreboardNumbers()) {
			return original.call(graphics, font, text, x, y, Mc263Overlay.scoreboardText(color), Mc263Overlay.scoreboardShadow(shadow));
		}
		return x;
	}

	// ---------- zoom (Zoomify) ----------

	/** Zoom: whether the spyglass overlay shows follows the zoom's spyglass overlay rule. */
	@ModifyExpressionValue(method = "renderCameraOverlays", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;isScoping()Z"))
	private boolean flint$spyglassOverlay(boolean scoping) {
		return com.flintclient.client.control.platform.Mc263Zoom.spyglassOverlay(scoping);
	}

	/** Zoom: the secondary zoom hides the whole HUD while it zooms, as F1 does. */
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void flint$secondaryZoomHud(CallbackInfo callback) {
		if (!com.flintclient.client.control.platform.Mc263Zoom.hudShown()) {
			callback.cancel();
		}
	}
}
