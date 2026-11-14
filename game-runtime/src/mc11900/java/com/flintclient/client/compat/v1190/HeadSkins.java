package com.flintclient.client.compat.v1190;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;

/** A player head's skin on Minecraft 1.19 (1.19.0), whose skin manager has no getInsecureSkinLocation (1.19.1 added it). */
public final class HeadSkins {
	private HeadSkins() {
	}

	/** The skin a player head with {@code profile} shows, as 1.19.1's getInsecureSkinLocation (and 1.19.0's head renderer) finds it. */
	public static ResourceLocation insecureSkinLocation(GameProfile profile) {
		SkinManager skins = Minecraft.getInstance().getSkinManager();
		MinecraftProfileTexture texture = skins.getInsecureSkinInformation(profile).get(MinecraftProfileTexture.Type.SKIN);
		return texture != null ? skins.registerTexture(texture, MinecraftProfileTexture.Type.SKIN)
			: DefaultPlayerSkin.getDefaultSkin(UUIDUtil.getOrCreatePlayerUUID(profile));
	}
}
