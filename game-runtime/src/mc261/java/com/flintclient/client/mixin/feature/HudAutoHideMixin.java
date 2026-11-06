package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.feature.AutoHide;
import com.flintclient.client.control.platform.Mc263AutoHide;
import com.flintclient.client.control.platform.Mc263Hotbar;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.contextualbar.ContextualBarRenderer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Minecraft's own HUD around the hotbar, moved as two Flint mods ask. Autohide HUD: each part (health and armor, food and
 * air, the hotbar and experience) is drawn as usual, lower down while it slides away, or not at all once hidden
 * ({@link Mc263AutoHide#shift}). The Hotbar mod: the hotbar and the bars above it (experience, health, armor, food, air, a
 * mount's health) are each drawn at their own size, stacked as Minecraft stacks them ({@link Mc263Hotbar}), and the held
 * item's name moves above them. Each is a translation and scale of the GUI pose around Minecraft's call; nothing is
 * allocated.
 */
@Mixin(Gui.class)
abstract class HudAutoHideMixin {
	/** {@link #flint$begin}'s answers: leave the part out, draw it as it is, or draw it moved (pop the pose after). */
	private static final int SKIP = 0;
	private static final int PLAIN = 1;
	private static final int MOVED = 2;

	/**
	 * Starts drawing {@code part} (Autohide HUD's part, or -1 for none) as the Hotbar mod's {@code piece}
	 * ({@link Mc263Hotbar#place}): sized around its anchor, then moved.
	 */
	private static int flint$begin(GuiGraphicsExtractor graphics, int part, int piece) {
		return flint$begin(graphics, part, piece, 0, 0);
	}

	/** {@link #flint$begin} for the armor row, which sits on {@code heartRows} heart rows {@code rowHeight} apart. */
	private static int flint$begin(GuiGraphicsExtractor graphics, int part, int piece, int heartRows, int rowHeight) {
		float shift = part < 0 ? 0 : Mc263AutoHide.shift(part);
		if (shift == Mc263AutoHide.GONE) {
			return SKIP;
		}
		boolean placed = Mc263Hotbar.place(piece, graphics.guiWidth(), graphics.guiHeight(), heartRows, rowHeight);
		if (shift == 0 && !placed) {
			return PLAIN;
		}
		graphics.pose().pushMatrix();
		if (!placed) {
			graphics.pose().translate(0, shift);
			return MOVED;
		}
		float scale = Mc263Hotbar.scale;
		float x = Mc263Hotbar.anchorX;
		float y = Mc263Hotbar.anchorY;
		graphics.pose().translate(Mc263Hotbar.moveX + x, Mc263Hotbar.moveY + shift + y);
		graphics.pose().scale(scale, scale);
		graphics.pose().translate(-x, -y);
		return MOVED;
	}

	private static void flint$end(GuiGraphicsExtractor graphics, int how) {
		if (how == MOVED) {
			graphics.pose().popMatrix();
		}
	}

	@WrapOperation(method = "extractPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;extractArmor(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IIII)V"))
	private void flint$armor(GuiGraphicsExtractor graphics, Player player, int a, int b, int c, int d, Operation<Void> original) {
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

	@WrapOperation(method = "extractPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;extractHearts(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IIIIFIIIZ)V"))
	private void flint$hearts(Gui hud, GuiGraphicsExtractor graphics, Player player, int a, int b, int c, int d, float e, int f, int g, int h,
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

	@WrapOperation(method = "extractPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;extractFood(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;II)V"))
	private void flint$food(Gui hud, GuiGraphicsExtractor graphics, Player player, int a, int b, Operation<Void> original) {
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

	@WrapOperation(method = "extractPlayerHealth", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;extractAirBubbles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;III)V"))
	private void flint$air(Gui hud, GuiGraphicsExtractor graphics, Player player, int a, int b, int c, Operation<Void> original) {
		int how = flint$begin(graphics, AutoHide.FOOD, Mc263Hotbar.HUNGER);
		if (how == SKIP) {
			return;
		}
		try {
			original.call(hud, graphics, player, a, b, c);
		} finally {
			flint$end(graphics, how);
		}
	}

	@WrapOperation(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;extractItemHotbar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
	private void flint$hotbar(Gui hud, GuiGraphicsExtractor graphics, DeltaTracker delta, Operation<Void> original) {
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

	@WrapOperation(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;extractVehicleHealth(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"))
	private void flint$mountHealth(Gui hud, GuiGraphicsExtractor graphics, Operation<Void> original) {
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

	@WrapOperation(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;extractSelectedItemName(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"))
	private void flint$itemName(Gui hud, GuiGraphicsExtractor graphics, Operation<Void> original) {
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

	@WrapOperation(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
	private void flint$barBackground(ContextualBarRenderer bar, GuiGraphicsExtractor graphics, DeltaTracker delta, Operation<Void> original) {
		int how = flint$begin(graphics, AutoHide.HOTBAR, Mc263Hotbar.EXPERIENCE);
		if (how == SKIP) {
			return;
		}
		try {
			original.call(bar, graphics, delta);
		} finally {
			flint$end(graphics, how);
		}
	}

	@WrapOperation(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;extractExperienceLevel(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V"))
	private void flint$level(GuiGraphicsExtractor graphics, Font font, int level, Operation<Void> original) {
		int how = flint$begin(graphics, AutoHide.HOTBAR, Mc263Hotbar.EXPERIENCE);
		if (how == SKIP) {
			return;
		}
		try {
			original.call(graphics, font, level);
		} finally {
			flint$end(graphics, how);
		}
	}

	@WrapOperation(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
	private void flint$bar(ContextualBarRenderer bar, GuiGraphicsExtractor graphics, DeltaTracker delta, Operation<Void> original) {
		int how = flint$begin(graphics, AutoHide.HOTBAR, Mc263Hotbar.EXPERIENCE);
		if (how == SKIP) {
			return;
		}
		try {
			original.call(bar, graphics, delta);
		} finally {
			flint$end(graphics, how);
		}
	}
}
