package com.flintclient.qa.mixin;

import com.flintclient.qa.QaSkin;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import java.util.Map;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 1.19.2: the QA player's look as a tour set it ({@link QaSkin}: its own skin, or the Minecraft cape stand-in),
 * written where Minecraft keeps a player's textures and model, as its skin service fills them in. Every reader asks
 * registerTextures first (the getters, and Flint's own hook on this class, which builds the skin with the Store cape from
 * these textures), so it's written at the end of that, and before the model's name is read (which doesn't register). Flint
 * then sees the stand-in as Minecraft's cape and decides over it, as it does under the getSkin hook on later versions.
 */
@Mixin(value = PlayerInfo.class, priority = 500)
abstract class PlayerInfoSkinMixin {
	@Unique
	private static boolean flintqa$broken;

	@Shadow
	@Final
	private GameProfile profile;
	@Shadow
	@Final
	private Map<MinecraftProfileTexture.Type, ResourceLocation> textureLocations;
	@Shadow
	private @Nullable String skinModel;

	/** What the tour wrote here, and Minecraft's own textures under it (made the first time the tour gives a look). */
	@Unique
	private QaSkin.@Nullable Written flintqa$written;

	@Inject(method = "registerTextures", at = @At("TAIL"))
	private void flintqa$textures(CallbackInfo ci) {
		flintqa$write();
	}

	@Inject(method = "getModelName", at = @At("HEAD"))
	private void flintqa$model(CallbackInfoReturnable<String> callback) {
		flintqa$write();
	}

	@Unique
	private void flintqa$write() {
		if (flintqa$broken || (flintqa$written == null && !QaSkin.active())) {
			return;
		}
		try {
			if (flintqa$written == null) {
				flintqa$written = new QaSkin.Written();
			}
			skinModel = QaSkin.write(profile.getId(), textureLocations, skinModel, flintqa$written);
		} catch (LinkageError e) {
			// A newer Minecraft than this QA build (a forward-compatibility run): as Minecraft has them.
			flintqa$broken = true;
		}
	}
}
