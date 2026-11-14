package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.gui.HudParts;
import com.flintclient.client.control.feature.AutoHide;
import com.flintclient.client.control.platform.Mc263AutoHide;
import com.flintclient.client.control.platform.Mc263Hotbar;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.19.2's own HUD around the hotbar, moved as two Flint mods ask (see the 1.21.11 version for the rules).
 * Autohide HUD: each part (health and armor, food and air, the hotbar and experience) is drawn as usual, lower down while it
 * slides away, or not at all once hidden ({@link Mc263AutoHide#shift}). The Hotbar mod: the hotbar and each bar above it are
 * drawn at their own sizes, stacked as Minecraft stacks them ({@link Mc263Hotbar}).
 *
 * <p>1.19.2 draws the hotbar, the bars and the item name from render, the hearts in renderHearts, and the armor, food and air
 * rows inline in renderPlayerHealth (each in its own profiler section): those rows are moved from their section's start to
 * its end and their blits left out when hidden (compat.gui.HudParts keeps how each is drawn, for the other HUD mixins). The
 * move is on the model-view pose, which 1.19.2's GUI draws everything through, its items too (the hotbar's items are drawn
 * there, not with the GUI pose); the experience level is drawn inside renderExperienceBar, so it moves with the bar.
 */
@Mixin(Gui.class)
abstract class HudAutoHideMixin {
	/**
	 * Starts drawing {@code part} (Autohide HUD's part, or -1 for none) as the Hotbar mod's {@code piece}
	 * ({@link Mc263Hotbar#place}): sized around its anchor, then moved.
	 */
	@Unique
	private static int flint$begin(int part, int piece) {
		return flint$begin(part, piece, 0, 0);
	}

	/** {@link #flint$begin} for the armor row, which sits on {@code heartRows} heart rows {@code rowHeight} apart. */
	@Unique
	private static int flint$begin(int part, int piece, int heartRows, int rowHeight) {
		float shift = part < 0 ? 0 : Mc263AutoHide.shift(part);
		if (shift == Mc263AutoHide.GONE) {
			return HudParts.SKIP;
		}
		com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
		boolean placed = Mc263Hotbar.place(piece, window.getGuiScaledWidth(), window.getGuiScaledHeight(), heartRows, rowHeight);
		if (shift == 0 && !placed) {
			return HudParts.PLAIN;
		}
		if (!placed) {
			HudParts.beginMoved(0.0F, shift, 1.0F, 0.0F, 0.0F);
			return HudParts.MOVED;
		}
		HudParts.beginMoved(Mc263Hotbar.moveX, Mc263Hotbar.moveY + shift, Mc263Hotbar.scale, Mc263Hotbar.anchorX, Mc263Hotbar.anchorY);
		return HudParts.MOVED;
	}

	@Unique
	private static void flint$end(int how) {
		if (how == HudParts.MOVED) {
			HudParts.endMoved();
		}
	}

	// ---------- the rows renderPlayerHealth draws inline ----------

	/** The armor row: from its section's start (the heart rows are laid out by then)... */
	@Inject(method = "renderPlayerHealth", at = @At(value = "INVOKE_STRING",
		target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", args = "ldc=armor"))
	private void flint$armorStart(PoseStack pose, CallbackInfo callback) {
		HudParts.armor = flint$begin(AutoHide.HEALTH, Mc263Hotbar.ARMOR, HudParts.heartRows, HudParts.rowHeight);
	}

	/** ...its icons left out once hidden... */
	@WrapWithCondition(method = "renderPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"),
		slice = @Slice(from = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V",
			args = "ldc=armor"), to = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
			args = "ldc=health")))
	private boolean flint$armor(Gui hud, PoseStack pose, int x, int y, int u, int v, int w, int h) {
		return HudParts.armor != HudParts.SKIP;
	}

	/** ...to the hearts' section. */
	@Inject(method = "renderPlayerHealth", at = @At(value = "INVOKE_STRING",
		target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=health"))
	private void flint$armorEnd(PoseStack pose, CallbackInfo callback) {
		flint$end(HudParts.armor);
		HudParts.armor = HudParts.PLAIN;
	}

	@WrapOperation(method = "renderPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;renderHearts(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/player/Player;IIIIFIIIZ)V"))
	private void flint$hearts(Gui hud, PoseStack pose, Player player, int a, int b, int c, int d, float e, int f, int g, int h, boolean i,
		Operation<Void> original) {
		int how = flint$begin(AutoHide.HEALTH, Mc263Hotbar.HEALTH);
		if (how == HudParts.SKIP) {
			return;
		}
		try {
			original.call(hud, pose, player, a, b, c, d, e, f, g, h, i);
		} finally {
			flint$end(how);
		}
	}

	/** The food row (drawn unless a mount's hearts take its place): its section, as the armor's. */
	@Inject(method = "renderPlayerHealth", at = @At(value = "INVOKE_STRING",
		target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=food"))
	private void flint$foodStart(PoseStack pose, CallbackInfo callback) {
		HudParts.food = flint$begin(AutoHide.FOOD, Mc263Hotbar.HUNGER);
	}

	@WrapWithCondition(method = "renderPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"),
		slice = @Slice(from = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
			args = "ldc=food"), to = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
			args = "ldc=air")))
	private boolean flint$food(Gui hud, PoseStack pose, int x, int y, int u, int v, int w, int h) {
		return HudParts.food != HudParts.SKIP;
	}

	/** The food row ends as the air row's section starts (after Saturation drew over it, HudFeatureMixin). */
	@Inject(method = "renderPlayerHealth", at = @At(value = "INVOKE_STRING", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=air"))
	private void flint$foodEnd(PoseStack pose, CallbackInfo callback) {
		flint$end(HudParts.food);
		HudParts.food = HudParts.PLAIN;
	}

	/** The air bubbles: moved with the food row (as on 1.20.2 and later), from their section's first call to its end. */
	@Inject(method = "renderPlayerHealth", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getMaxAirSupply()I"))
	private void flint$airStart(PoseStack pose, CallbackInfo callback) {
		HudParts.air = flint$begin(AutoHide.FOOD, Mc263Hotbar.HUNGER);
	}

	@WrapWithCondition(method = "renderPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"),
		slice = @Slice(from = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
			args = "ldc=air")))
	private boolean flint$air(Gui hud, PoseStack pose, int x, int y, int u, int v, int w, int h) {
		return HudParts.air != HudParts.SKIP;
	}

	@Inject(method = "renderPlayerHealth", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V"))
	private void flint$airEnd(PoseStack pose, CallbackInfo callback) {
		flint$end(HudParts.air);
		HudParts.air = HudParts.PLAIN;
	}

	// ---------- what render draws itself ----------

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderHotbar(FLcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private void flint$hotbar(Gui hud, float partialTick, PoseStack pose, Operation<Void> original) {
		int how = flint$begin(AutoHide.HOTBAR, Mc263Hotbar.HOTBAR);
		if (how == HudParts.SKIP) {
			return;
		}
		try {
			original.call(hud, partialTick, pose);
		} finally {
			flint$end(how);
		}
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderVehicleHealth(Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private void flint$mountHealth(Gui hud, PoseStack pose, Operation<Void> original) {
		int how = flint$begin(AutoHide.FOOD, Mc263Hotbar.HUNGER);
		if (how == HudParts.SKIP) {
			return;
		}
		try {
			original.call(hud, pose);
		} finally {
			flint$end(how);
		}
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderSelectedItemName(Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private void flint$itemName(Gui hud, PoseStack pose, Operation<Void> original) {
		// Item Info shows the name (and more) in its own place.
		if (com.flintclient.client.control.platform.Mc263HudExtras.replacesItemName()) {
			return;
		}
		int how = flint$begin(-1, Mc263Hotbar.ITEM_NAME);
		try {
			original.call(hud, pose);
		} finally {
			flint$end(how);
		}
	}

	/** The experience bar, with the level 1.19.2 draws over it in the same method. */
	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderExperienceBar(Lcom/mojang/blaze3d/vertex/PoseStack;I)V"))
	private void flint$experienceBar(Gui hud, PoseStack pose, int x, Operation<Void> original) {
		int how = flint$begin(AutoHide.HOTBAR, Mc263Hotbar.EXPERIENCE);
		if (how == HudParts.SKIP) {
			return;
		}
		try {
			original.call(hud, pose, x);
		} finally {
			flint$end(how);
		}
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;renderJumpMeter(Lcom/mojang/blaze3d/vertex/PoseStack;I)V"))
	private void flint$jumpMeter(Gui hud, PoseStack pose, int x, Operation<Void> original) {
		int how = flint$begin(AutoHide.HOTBAR, Mc263Hotbar.EXPERIENCE);
		if (how == HudParts.SKIP) {
			return;
		}
		try {
			original.call(hud, pose, x);
		} finally {
			flint$end(how);
		}
	}
}
