package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263MoreMods;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.SubtitleOverlay;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Subtitles: each subtitle in its kind of sound's colour, as it is added. Armor's Push subtitles: they move up above its
 * strip when it sits in the bottom right corner.
 *
 * <p>While Subtitles' Nearby Sound Activity shows the sounds itself, Minecraft's own subtitles stand aside, so no caption
 * shows twice: as the overlay draws, its two reads of the Show Subtitles option read off, which also takes its listener off
 * the sound engine (it hears nothing meanwhile). The option itself is never written, so turning the list off brings
 * Minecraft's subtitles back exactly as the player had them.
 */
@Mixin(SubtitleOverlay.class)
abstract class SubtitleOverlayMixin {
	@ModifyExpressionValue(method = "onPlaySound", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/sounds/WeighedSoundEvents;getSubtitle()Lnet/minecraft/network/chat/Component;"))
	private Component flint$colour(Component subtitle, @Local(argsOnly = true) SoundInstance sound) {
		return subtitle == null ? null : Mc263MoreMods.subtitle(subtitle, sound);
	}

	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE", target = "Ljava/lang/Boolean;booleanValue()Z"))
	private boolean flint$standAside(boolean showSubtitles) {
		return showSubtitles && !com.flintclient.client.control.platform.Mc263SoundActivity.replacesVanilla();
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void flint$armorPushBegin(GuiGraphicsExtractor graphics, CallbackInfo callback) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(0.0F, -com.flintclient.client.control.platform.Mc263ArmorHud.subtitlesPush(graphics));
	}

	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void flint$armorPushEnd(GuiGraphicsExtractor graphics, CallbackInfo callback) {
		graphics.pose().popMatrix();
	}
}
