package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.feature.AutoHide;
import com.flintclient.client.control.platform.Mc263AutoHide;
import com.flintclient.client.control.platform.Mc263Hotbar;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.21.1's own HUD around the hotbar, moved as two Flint mods ask (see the 1.21.11 version for the rules).
 * Autohide HUD: each part (health and armor, food and air, the hotbar and experience) is drawn as usual, lower down while it
 * slides away, or not at all once hidden ({@link Mc263AutoHide#shift}). The Hotbar mod: the hotbar and each bar above it are
 * drawn at their own sizes, stacked as Minecraft stacks them ({@link Mc263Hotbar}). 1.21.1 draws the air bubbles inside
 * renderPlayerHealth and the experience level as its own HUD layer, so those two are moved where they're drawn. Each is a
 * translation and scale of the GUI pose around Minecraft's call; nothing is allocated.
 */
@Mixin(Gui.class)
abstract class HudAutoHideMixin {
	/** {@link #flint$begin}'s answers: leave the part out, draw it as it is, or draw it moved (pop the pose after). */
	@Unique
	private static final int SKIP = 0;
	@Unique
	private static final int PLAIN = 1;
	@Unique
	private static final int MOVED = 2;

	/** How the experience level (its own HUD layer) was started, for its end. */
	@Unique
	private static int flint$levelHow = PLAIN;

	/**
	 * Starts drawing {@code part} (Autohide HUD's part, or -1 for none) as the Hotbar mod's {@code piece}
	 * ({@link Mc263Hotbar#place}): sized around its anchor, then moved.
	 */
	@Unique
	private static int flint$begin(GuiGraphics graphics, int part, int piece) {
		return flint$begin(graphics, part, piece, 0, 0);
	}

	/** {@link #flint$begin} for the armor row, which sits on {@code heartRows} heart rows {@code rowHeight} apart. */
	@Unique
	private static int flint$begin(GuiGraphics graphics, int part, int piece, int heartRows, int rowHeight) {
		float shift = part < 0 ? 0 : Mc263AutoHide.shift(part);
		if (shift == Mc263AutoHide.GONE) {
			return SKIP;
		}
		boolean placed = Mc263Hotbar.place(piece, graphics.guiWidth(), graphics.guiHeight(), heartRows, rowHeight);
		if (shift == 0 && !placed) {
			return PLAIN;
		}
		graphics.pose().pushPose();
		if (!placed) {
			graphics.pose().translate(0, shift, 0);
			return MOVED;
		}
		float scale = Mc263Hotbar.scale;
		float x = Mc263Hotbar.anchorX;
		float y = Mc263Hotbar.anchorY;
		graphics.pose().translate(Mc263Hotbar.moveX + x, Mc263Hotbar.moveY + shift + y, 0);
		graphics.pose().scale(scale, scale, 1);
		graphics.pose().translate(-x, -y, 0);
		return MOVED;
	}

	@Unique
	private static void flint$end(GuiGraphics graphics, int how) {
		if (how == MOVED) {
			graphics.pose().popPose();
		}
	}

	@WrapOperation(method = "renderPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;renderArmor(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/entity/player/Player;IIII)V"))
	private void flint$armor(GuiGraphics graphics, Player player, int a, int b, int c, int d, Operation<Void> original) {
		int how = flint$begin(graphics, AutoHide.HEALTH, Mc263Hotbar.ARMOR, b, c);
		if (how == SKIP) {
			return;
		}
		try {
			original.call(graphics, player, a, b, c, d);
		} finally {
			flint$end(graphics, how);
		}
	}

	@WrapOperation(method = "renderPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;renderHearts(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/entity/player/Player;IIIIFIIIZ)V"))
	private void flint$hearts(Gui hud, GuiGraphics graphics, Player player, int a, int b, int c, int d, float e, int f, int g, int h,
		boolean i, Operation<Void> original) {
		int how = flint$begin(graphics, AutoHide.HEALTH, Mc263Hotbar.HEALTH);
		if (how == SKIP) {
			return;
		}
		try {
			original.call(hud, graphics, player, a, b, c, d, e, f, g, h, i);
		} finally {
			flint$end(graphics, how);
		}
	}

	@WrapOperation(method = "renderPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;renderFood(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/entity/player/Player;II)V"))
	private void flint$food(Gui hud, GuiGraphics graphics, Player player, int a, int b, Operation<Void> original) {
		int how = flint$begin(graphics, AutoHide.FOOD, Mc263Hotbar.HUNGER);
		if (how == SKIP) {
			return;
		}
		try {
			original.call(hud, graphics, player, a, b);
		} finally {
			flint$end(graphics, how);
		}
	}

	/** The air bubbles: the only sprites renderPlayerHealth draws itself. */
	@WrapOperation(method = "renderPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
	private void flint$air(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height, Operation<Void> original) {
		int how = flint$begin(graphics, AutoHide.FOOD, Mc263Hotbar.HUNGER);
		if (how == SKIP) {
			return;
		}
		try {
			original.call(graphics, sprite, x, y, width, height);
		} finally {
			flint$end(graphics, how);
		}
	}

	@WrapOperation(method = "renderHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;renderItemHotbar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"))
	private void flint$hotbar(Gui hud, GuiGraphics graphics, DeltaTracker delta, Operation<Void> original) {
		int how = flint$begin(graphics, AutoHide.HOTBAR, Mc263Hotbar.HOTBAR);
		if (how == SKIP) {
			return;
		}
		try {
			original.call(hud, graphics, delta);
		} finally {
			flint$end(graphics, how);
		}
	}

	@WrapOperation(method = "renderHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;renderVehicleHealth(Lnet/minecraft/client/gui/GuiGraphics;)V"))
	private void flint$mountHealth(Gui hud, GuiGraphics graphics, Operation<Void> original) {
		int how = flint$begin(graphics, AutoHide.FOOD, Mc263Hotbar.HUNGER);
		if (how == SKIP) {
			return;
		}
		try {
			original.call(hud, graphics);
		} finally {
			flint$end(graphics, how);
		}
	}

	@WrapOperation(method = "renderHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;renderSelectedItemName(Lnet/minecraft/client/gui/GuiGraphics;)V"))
	private void flint$itemName(Gui hud, GuiGraphics graphics, Operation<Void> original) {
		// Item Info shows the name (and more) in its own place.
		if (com.flintclient.client.control.platform.Mc263HudExtras.replacesItemName()) {
			return;
		}
		int how = flint$begin(graphics, -1, Mc263Hotbar.ITEM_NAME);
		try {
			original.call(hud, graphics);
		} finally {
			flint$end(graphics, how);
		}
	}

	@WrapOperation(method = "renderHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;renderExperienceBar(Lnet/minecraft/client/gui/GuiGraphics;I)V"))
	private void flint$experienceBar(Gui hud, GuiGraphics graphics, int x, Operation<Void> original) {
		int how = flint$begin(graphics, AutoHide.HOTBAR, Mc263Hotbar.EXPERIENCE);
		if (how == SKIP) {
			return;
		}
		try {
			original.call(hud, graphics, x);
		} finally {
			flint$end(graphics, how);
		}
	}

	@WrapOperation(method = "renderHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;renderJumpMeter(Lnet/minecraft/world/entity/PlayerRideableJumping;Lnet/minecraft/client/gui/GuiGraphics;I)V"))
	private void flint$jumpMeter(Gui hud, PlayerRideableJumping mount, GuiGraphics graphics, int x, Operation<Void> original) {
		int how = flint$begin(graphics, AutoHide.HOTBAR, Mc263Hotbar.EXPERIENCE);
		if (how == SKIP) {
			return;
		}
		try {
			original.call(hud, mount, graphics, x);
		} finally {
			flint$end(graphics, how);
		}
	}

	@Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
	private void flint$levelStart(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
		flint$levelHow = flint$begin(graphics, AutoHide.HOTBAR, Mc263Hotbar.EXPERIENCE);
		if (flint$levelHow == SKIP) {
			flint$levelHow = PLAIN;
			callback.cancel();
		}
	}

	@Inject(method = "renderExperienceLevel", at = @At("RETURN"))
	private void flint$levelEnd(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
		flint$end(graphics, flint$levelHow);
		flint$levelHow = PLAIN;
	}
}
