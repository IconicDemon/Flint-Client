package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.DeltaTracker;
import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.compat.gui.HudParts;
import com.flintclient.client.control.platform.Mc263Features;
import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The HUD-side modules on Minecraft 1.19.2's own HUD: the hotbar's extras, Armor, Status Effect Bars, Saturation, Chat's
 * toggle, the tab list key, Titles (hide, print to chat, tweaks) and Scoreboard (hide, numbers, background, shadow). Boss
 * bars are BossHealthOverlayMixin's. 1.19.2 draws most of its HUD inline in render (the title and subtitle, chat, the tab
 * list) and in renderPlayerHealth (the food row), so those hooks sit on the calls and profiler sections there. It draws its
 * icons from sheets it binds once (icons.png, the inventory texture), so they are bound again after Flint draws among them
 * (compat.gui.HudParts).
 */
@Mixin(Gui.class)
abstract class HudFeatureMixin implements com.flintclient.client.control.platform.Mc263OriginalHud.ActionBar {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	private int overlayMessageTime;
	@Shadow
	private int screenWidth;
	@Shadow
	private int screenHeight;
	@Shadow
	private @Nullable Component title;
	@Shadow
	private @Nullable Component subtitle;

	@Shadow
	private Player getCameraPlayer() {
		throw new AssertionError();
	}

	@Override
	public boolean flint$actionBarShowing() {
		return overlayMessageTime > 0;
	}

	// ---------- hotbar ----------

	/** Inventory: the hotbar's slot decorations and each slot's key on the slot, after the hotbar is drawn; Loot Beams' tooltip. */
	@Inject(method = "renderHotbar", at = @At("TAIL"))
	private void flint$hotbarKeys(float partialTick, PoseStack pose, CallbackInfo callback) {
		GuiGraphics graphics = GuiGraphics.of(pose);
		com.flintclient.client.control.platform.Mc263ArmorHud.draw(graphics, DeltaTracker.GAME);
		com.flintclient.client.control.platform.Mc263Inventory.hotbarDecorations(graphics);
		com.flintclient.client.control.platform.Mc263Inventory.hotbarKeys(graphics);
		com.flintclient.client.control.platform.Mc263Tools.hotbar(graphics);
		com.flintclient.client.control.platform.Mc263LootBeams.hud(graphics);
	}

	/**
	 * Armor: the held items' durability over their hotbar slots, after the hotbar and its bars (the end of the part F1 hides
	 * that 1.20.2 and later draw as renderHotbarAndDecorations; 1.19.2 goes on to the sleep overlay next).
	 */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getSleepTimer()I", ordinal = 0))
	private void flint$armorHands(PoseStack pose, float partialTick, CallbackInfo callback) {
		if (!minecraft.options.hideGui) {
			com.flintclient.client.control.platform.Mc263ArmorHud.drawHands(GuiGraphics.of(pose));
		}
	}

	// ---------- effects ----------

	/** Armor's Push effect icons: Minecraft's effect icons move down below its strip. */
	@Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
	private void flint$effectsPushBegin(PoseStack pose, CallbackInfo callback) {
		// Hidden while Flint draws its own.
		if (com.flintclient.client.control.ControlCenter.hideVanillaEffects()) {
			callback.cancel();
			return;
		}
		GuiGraphics graphics = GuiGraphics.of(pose);
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
		target = "Lnet/minecraft/world/effect/MobEffectInstance;getEffect()Lnet/minecraft/world/effect/MobEffect;"))
	private MobEffect flint$barEffect(MobEffectInstance instance, Operation<MobEffect> original) {
		com.flintclient.client.control.platform.Mc263EffectBars.drawing(instance, true);
		return original.call(instance);
	}

	/**
	 * The bar over the icon's frame (where Minecraft places that icon: by kind, from the right), as the icon's sprite is
	 * fetched; the inventory texture the frames come from is bound again for the next one.
	 */
	@Inject(method = "renderEffects", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/resources/MobEffectTextureManager;get(Lnet/minecraft/world/effect/MobEffect;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
	private void flint$bar(PoseStack pose, CallbackInfo callback) {
		MobEffectInstance effect = com.flintclient.client.control.platform.Mc263EffectBars.drawn(true);
		if (effect == null) {
			return;
		}
		int x = screenWidth;
		int y = 1;
		if (minecraft.isDemo()) {
			y += 15;
		}
		if (effect.getEffect().isBeneficial()) {
			x -= 25 * ++flint$beneficialIcons;
		} else {
			x -= 25 * ++flint$harmfulIcons;
			y += 26;
		}
		com.flintclient.client.control.platform.Mc263EffectBars.render(GuiGraphics.of(pose), effect, x, y, 24, 24, true);
		HudParts.restore(AbstractContainerScreen.INVENTORY_LOCATION);
	}

	@Inject(method = "renderEffects", at = @At("RETURN"))
	private void flint$effectsPushEnd(PoseStack pose, CallbackInfo callback) {
		GuiGraphics.of(pose).pose2d().popMatrix();
	}

	// ---------- Saturation ----------

	/** Whether renderPlayerHealth draws the food row this frame (it doesn't while riding a mount with hearts). */
	@Unique
	private boolean flint$foodRow;

	@Inject(method = "renderPlayerHealth", at = @At(value = "INVOKE_STRING",
		target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=food"))
	private void flint$foodRowStarts(PoseStack pose, CallbackInfo callback) {
		flint$foodRow = true;
	}

	/** Saturation and food gain over the hunger bar, once its drumsticks are drawn (as the air row starts). */
	@Inject(method = "renderPlayerHealth", at = @At(value = "INVOKE_STRING",
		target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=air"))
	private void flint$food(PoseStack pose, CallbackInfo callback) {
		if (!flint$foodRow) {
			return;
		}
		flint$foodRow = false;
		if (HudParts.food == HudParts.SKIP) {
			return;
		}
		com.flintclient.client.control.platform.Mc263Food.foodBar(GuiGraphics.of(pose), getCameraPlayer(), screenHeight - 39, screenWidth / 2 + 91);
		HudParts.icons();
	}

	/** The health held food would give back, on the hearts. */
	@Inject(method = "renderHearts", at = @At("TAIL"))
	private void flint$healthGain(PoseStack pose, Player player, int x, int y, int rowHeight, int regenIndex, float maxHealth, int health,
		int displayHealth, int absorption, boolean blink, CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263Food.hearts(GuiGraphics.of(pose), player, x, y, rowHeight, maxHealth);
		HudParts.icons();
	}

	// ---------- chat and the tab list ----------

	/** Chat: hidden from the HUD by its toggle key (the chat call only, so Flint's HUD, drawn just before it, stays). */
	@WrapWithCondition(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/ChatComponent;render(Lcom/mojang/blaze3d/vertex/PoseStack;I)V"))
	private boolean flint$chat(ChatComponent chat, PoseStack pose, int tick) {
		return !com.flintclient.client.control.platform.Mc263Chat.hideOnHud();
	}

	/** Tab Editor's Toggle tablist: a tap shows the list until the next tap. */
	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
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
	private void flint$afterCrosshair(PoseStack pose, CallbackInfo callback) {
		Mc263Overlay.afterCrosshair(GuiGraphics.of(pose));
	}

	// ---------- titles ----------

	/** Titles hidden: 1.19.2 draws them inline when there is one, so the title reads as none. */
	@ModifyExpressionValue(method = "render", at = @At(value = "FIELD", ordinal = 0,
		target = "Lnet/minecraft/client/gui/Gui;title:Lnet/minecraft/network/chat/Component;"))
	private @Nullable Component flint$hideTitles(@Nullable Component shown) {
		return Mc263Features.showTitles() ? shown : null;
	}

	/** Title Tweaks on the title (drawn at 4×): its scale, vertical position, opacity and shadow. 1.19.2 draws its backdrop... */
	@WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/Gui;drawBackdrop(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;III)V"))
	private void flint$titleBackdrop(Gui hud, PoseStack pose, Font font, int y, int width, int color, Operation<Void> original) {
		flint$tweakedBackdrop(hud, pose, font, y, width, color, original, title, false);
	}

	/** ...and its text apart. */
	@WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/Font;drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"))
	private int flint$title(Font font, PoseStack pose, Component text, float x, float y, int color, Operation<Integer> original) {
		return flint$tweakedTitle(font, pose, text, x, y, color, original, false);
	}

	/** Title Tweaks on the subtitle (drawn at 2×). */
	@WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 2,
		target = "Lnet/minecraft/client/gui/Gui;drawBackdrop(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;III)V"))
	private void flint$subtitleBackdrop(Gui hud, PoseStack pose, Font font, int y, int width, int color, Operation<Void> original) {
		flint$tweakedBackdrop(hud, pose, font, y, width, color, original, subtitle, true);
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 2,
		target = "Lnet/minecraft/client/gui/Font;drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"))
	private int flint$subtitle(Font font, PoseStack pose, Component text, float x, float y, int color, Operation<Integer> original) {
		return flint$tweakedTitle(font, pose, text, x, y, color, original, true);
	}

	/** A title's backdrop, scaled and placed with its text; none when the text has no shadow (as 1.21.1's tweaked title). */
	@Unique
	private void flint$tweakedBackdrop(Gui hud, PoseStack pose, Font font, int y, int width, int color, Operation<Void> original,
		@Nullable Component text, boolean subtitle) {
		if (!Mc263Features.tweakTitles() || text == null) {
			original.call(hud, pose, font, y, width, color);
			return;
		}
		if (Mc263Features.titleNoShadow()) {
			return;
		}
		float scale = Mc263Features.titleScale(font, text, subtitle, screenWidth);
		pose.pushPose();
		pose.scale(scale, scale, 1.0F);
		try {
			original.call(hud, pose, font, y - Mc263Features.titleOffset(subtitle), width, Mc263Features.titleColor(color));
		} finally {
			pose.popPose();
		}
	}

	@Unique
	private int flint$tweakedTitle(Font font, PoseStack pose, Component text, float x, float y, int color, Operation<Integer> original,
		boolean subtitle) {
		if (!Mc263Features.tweakTitles()) {
			return original.call(font, pose, text, x, y, color);
		}
		float scale = Mc263Features.titleScale(font, text, subtitle, screenWidth);
		int ink = Mc263Features.titleColor(color);
		float at = y - Mc263Features.titleOffset(subtitle);
		pose.pushPose();
		pose.scale(scale, scale, 1.0F);
		try {
			return Mc263Features.titleNoShadow() ? font.draw(pose, text, x, at, ink) : original.call(font, pose, text, x, at, ink);
		} finally {
			pose.popPose();
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

	/** Whether this sidebar's backgrounds are drawn yet (all at once, before its first row's text). */
	@Unique
	private boolean flint$sidebarFilled;

	/**
	 * Scoreboard hidden, or by Custom F3 (BetterF3) while the debug screen is open; otherwise the sidebar moved where
	 * Flint's HUD placed it. 1.19.2 calls this from render alone, so both hides are here, first.
	 */
	@Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
	private void flint$scoreboardBegin(PoseStack pose, net.minecraft.world.scores.Objective objective, CallbackInfo callback) {
		if (Mc263Overlay.hideScoreboard() || com.flintclient.client.control.platform.Mc263BetterF3.hideSidebar()) {
			callback.cancel();
			return;
		}
		flint$sidebarFilled = false;
		Mc263Overlay.scoreboardBegin(GuiGraphics.of(pose));
	}

	@Inject(method = "displayScoreboardSidebar", at = @At("RETURN"))
	private void flint$scoreboardEnd(PoseStack pose, net.minecraft.world.scores.Objective objective, CallbackInfo callback) {
		Mc263Overlay.scoreboardEnd(GuiGraphics.of(pose));
	}

	/**
	 * The sidebar's backgrounds. 1.19.2 fills each row as it draws it, from the bottom up, and the title's last; 1.20.3 and
	 * later fill the title's, then all the lines' at once, which is what Scoreboard's colours and border are drawn on. So at
	 * the first (bottom) row's fill both are drawn, in that order, and Minecraft's own fills are left out: the rows span
	 * from that row's bottom up {@code (bottom - screenHeight / 2) / 3} rows (1.19.2 centres them on a third of their height).
	 */
	@WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;fill(Lcom/mojang/blaze3d/vertex/PoseStack;IIIII)V"))
	private void flint$scoreboardFill(PoseStack pose, int x0, int y0, int x1, int y1, int color, Operation<Void> original) {
		if (flint$sidebarFilled) {
			return;
		}
		flint$sidebarFilled = true;
		int rows = Math.max(1, (y1 - screenHeight / 2) / 3);
		int top = y1 - rows * 9;
		GuiGraphics graphics = GuiGraphics.of(pose);
		int titleColor = minecraft.options.getBackgroundColor(Mc263Overlay.scoreboardOpacity(0.4F));
		Mc263Overlay.scoreboardFill(graphics, x0, top - 9 - 1, x1, top - 1, titleColor, true);
		Mc263Overlay.scoreboardFill(graphics, x0, top - 1, x1, y1, color, false);
	}

	@WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getBackgroundColor(F)I"))
	private int flint$scoreboardBackground(Options options, float opacity, Operation<Integer> original) {
		return original.call(options, Mc263Overlay.scoreboardOpacity(opacity));
	}

	/** The objective's name (1.19.2 draws it with the top row, after the rows' names). */
	@WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/Font;draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"))
	private int flint$scoreboardTitle(Font font, PoseStack pose, Component text, float x, float y, int color, Operation<Integer> original) {
		return flint$sidebarText(font, pose, text, x, y, color, original);
	}

	@WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/Font;draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"))
	private int flint$scoreboardName(Font font, PoseStack pose, Component text, float x, float y, int color, Operation<Integer> original) {
		return flint$sidebarText(font, pose, text, x, y, color, original);
	}

	/** A row's score (1.19.2 draws it as a string). */
	@WrapOperation(method = "displayScoreboardSidebar", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/Font;draw(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/lang/String;FFI)I"))
	private int flint$scoreboardNumber(Font font, PoseStack pose, String text, float x, float y, int color, Operation<Integer> original) {
		if (!Mc263Overlay.scoreboardNumbers()) {
			return (int) x;
		}
		int ink = Mc263Overlay.scoreboardText(color);
		return Mc263Overlay.scoreboardShadow(false) ? font.drawShadow(pose, text, x, y, ink) : original.call(font, pose, text, x, y, ink);
	}

	@Unique
	private static int flint$sidebarText(Font font, PoseStack pose, Component text, float x, float y, int color, Operation<Integer> original) {
		int ink = Mc263Overlay.scoreboardText(color);
		return Mc263Overlay.scoreboardShadow(false) ? font.drawShadow(pose, text, x, y, ink) : original.call(font, pose, text, x, y, ink);
	}

	// ---------- zoom (Zoomify) ----------

	/** Zoom: whether the spyglass overlay shows follows the zoom's spyglass overlay rule. */
	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isScoping()Z"))
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
