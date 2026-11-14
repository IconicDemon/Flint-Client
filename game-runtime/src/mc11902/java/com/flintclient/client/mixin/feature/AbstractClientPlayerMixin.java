package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.PlayerSkin;
import com.flintclient.client.compat.render.SkinBridge;
import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FOV Changer: Minecraft's FOV effects (flying, speed, bow, spyglass), each scaled by the module's percentage. Minecraft
 * 1.19.2 reads the camera mode and the FOV effect scale itself, so they're read here the same way.
 *
 * <p>And a player's skin as 1.20.2's {@code getSkin()} hands it (SkinBridge): their player info's (Flint Store cape and Nick
 * Hider included, PlayerInfoMixin), or the default skin 1.19.2 gives a player without one.
 */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin implements SkinBridge {
	@Shadow
	protected abstract @Nullable PlayerInfo getPlayerInfo();

	@Override
	public PlayerSkin getSkin() {
		PlayerInfo info = getPlayerInfo();
		return info == null ? SkinBridge.byDefault(((AbstractClientPlayer) (Object) this).getUUID()) : ((SkinBridge) info).getSkin();
	}

	@Inject(method = "getFieldOfViewModifier", at = @At("HEAD"), cancellable = true)
	private void flint$fovChanger(CallbackInfoReturnable<Float> callback) {
		Minecraft mc = Minecraft.getInstance();
		float modifier = Mc263Features.fovModifier((AbstractClientPlayer) (Object) this, mc.options.getCameraType().isFirstPerson(),
			mc.options.fovEffectScale().get().floatValue());
		if (!Float.isNaN(modifier)) {
			callback.setReturnValue(modifier);
		}
	}

	/** Zoom (Zoomify): the spyglass's own zoom (0.1) applies only when the two combine. */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "getFieldOfViewModifier", at = @At(value = "CONSTANT",
		args = "floatValue=0.1F"))
	private float flint$spyglassFov(float multiplier) {
		return com.flintclient.client.control.platform.Mc263Zoom.spyglassFov(multiplier);
	}
}
