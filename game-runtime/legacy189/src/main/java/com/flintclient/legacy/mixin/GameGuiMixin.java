package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.platform.HudData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GameGui;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.Window;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flint's HUD in Minecraft's: the shared HUD (every line's) is drawn just before chat (so F1 hides it with the rest, and chat
 * and the player list draw over it); Custom Crosshair takes the crosshair's place, Scoreboard the sidebar's (where the HUD
 * editor puts it), Armor draws right after the hotbar and Saturation over the hunger bar right after it.
 */
@Mixin(GameGui.class)
public abstract class GameGuiMixin extends GuiElement {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	private int ticks;

	@Shadow
	protected abstract boolean hasCrosshair();

	@Shadow
	private void renderScoreboardObjective(ScoreboardObjective objective, Window window) {
	}

	/** Waypoint labels, under everything else on the HUD (after Minecraft's GUI projection and vignette, as wWaypoints draws them). */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/living/player/PlayerInventory;getArmor(I)Lnet/minecraft/item/ItemStack;"))
	private void flint$waypointLabels(float tickDelta, CallbackInfo ci) {
		if (Flint.ready() && Flint.modules().waypoints.enabled()) {
			try {
				com.flintclient.legacy.waypoints.WaypointRenderer.labels(tickDelta);
			} catch (RuntimeException e) {
				Flint.fault(Flint.modules().waypoints, e);
			}
		}
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/chat/ChatGui;render(I)V"))
	private void flint$hud(float tickDelta, CallbackInfo ci) {
		if (!Flint.ready() || !Flint.hudOn()) {
			return;
		}
		// The shared HUD sets up its own projection (window pixels), so where Minecraft has moved to for chat doesn't matter.
		long start = com.flintclient.legacy.HudTiming.ON ? com.flintclient.legacy.HudTiming.start() : 0;
		try {
			HudRenderer.render(Frame.get());
		} catch (RuntimeException | LinkageError e) {
			Flint.hudFailed(e);
		} finally {
			GlStateManager.disableAlphaTest();
			Flint.modules().scoreboard.frameEnd();
			if (com.flintclient.legacy.HudTiming.ON) {
				com.flintclient.legacy.HudTiming.add(start);
			}
		}
	}

	/** Armor, right after the hotbar (so F1 and the spectator menu hide it as they hide the hotbar). */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GameGui;renderHotbar(Lnet/minecraft/client/render/Window;F)V",
		shift = At.Shift.AFTER))
	private void flint$armor(float tickDelta, CallbackInfo ci) {
		if (Flint.ready() && Flint.modules().armorStatus.enabled()) {
			long start = com.flintclient.legacy.HudTiming.ON ? com.flintclient.legacy.HudTiming.start() : 0;
			try {
				Flint.modules().armorStatus.draw(HudData.guiWidth(), HudData.guiHeight());
			} catch (RuntimeException e) {
				Flint.fault(Flint.modules().armorStatus, e);
			}
			if (com.flintclient.legacy.HudTiming.ON) {
				com.flintclient.legacy.HudTiming.add(start);
			}
			minecraft.getTextureManager().bind(ICONS_LOCATION);
		}
	}

	@Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GameGui;hasCrosshair()Z"))
	private boolean flint$crosshair(GameGui gui) {
		boolean shown = hasCrosshair();
		if (!Flint.ready() || !Flint.modules().crosshair.replaces()) {
			return shown;
		}
		if (shown) {
			try {
				Flint.modules().crosshair.draw();
			} catch (RuntimeException e) {
				Flint.fault(Flint.modules().crosshair, e);
			}
			minecraft.getTextureManager().bind(ICONS_LOCATION);
			GlStateManager.enableBlend();
		}
		return false;
	}

	@Redirect(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GameGui;renderScoreboardObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;Lnet/minecraft/client/render/Window;)V"))
	private void flint$scoreboard(GameGui gui, ScoreboardObjective objective, Window window) {
		if (Flint.ready() && Flint.modules().scoreboard.enabled()) {
			long start = com.flintclient.legacy.HudTiming.ON ? com.flintclient.legacy.HudTiming.start() : 0;
			Flint.modules().scoreboard.draw(objective, ticks);
			if (com.flintclient.legacy.HudTiming.ON) {
				com.flintclient.legacy.HudTiming.add(start);
			}
		} else {
			renderScoreboardObjective(objective, window);
		}
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GameGui;renderStatusBars(Lnet/minecraft/client/render/Window;)V",
		shift = At.Shift.AFTER))
	private void flint$saturation(float tickDelta, CallbackInfo ci) {
		if (Flint.ready() && Flint.modules().saturation.enabled()) {
			Flint.modules().saturation.drawOverlay(HudData.guiWidth(), HudData.guiHeight());
			minecraft.getTextureManager().bind(ICONS_LOCATION);
		}
	}
}
