package com.flintclient.client.compat.screens;

import com.flintclient.client.compat.PlayerSkin;
import com.flintclient.client.compat.render.SkinBridge;
import com.google.common.collect.Iterables;
import com.mojang.authlib.Agent;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.minecraft.InsecureTextureException;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * 1.20.2's profile and skin lookups on Minecraft 1.19.2, whose skin manager registers a profile's textures one callback per
 * texture and has no skin record ({@link PlayerSkin} stands in): a profile's skin as a future, or as a supplier answering
 * Minecraft's default until it is known; the signed-in player's profile with its properties; a player's profile by name.
 */
public final class Skins {
	private Skins() {
	}

	/**
	 * 1.20.2's {@code Minecraft.getGameProfile()}: the signed-in player's profile with its properties. 1.19.2 fetches those the
	 * first time they are asked for and keeps them (an account switch empties them), so this blocks: off the render thread. When
	 * the fetch failed the profile has no properties (and so the default skin).
	 */
	public static GameProfile ownProfile(Minecraft minecraft) {
		GameProfile user = minecraft.getUser().getGameProfile();
		GameProfile profile = new GameProfile(user.getId(), user.getName());
		profile.getProperties().putAll(minecraft.getProfileProperties());
		return profile;
	}

	/**
	 * 1.20.2's {@code SkinManager.getOrLoad}: the profile's skin, cape and elytra, registered with Minecraft's skin cache (a skin
	 * still downloading shows Minecraft's default meanwhile, as 1.19.2's do), signed or not ({@link PlayerSkin#secure}). A profile
	 * with no textures has the default skin for its id.
	 */
	public static CompletableFuture<PlayerSkin> getOrLoad(Minecraft minecraft, GameProfile profile) {
		return CompletableFuture.supplyAsync(() -> textures(minecraft, profile), Util.backgroundExecutor())
			.thenApplyAsync(textures -> register(minecraft, profile, textures), minecraft);
	}

	/** 1.20.2's {@code SkinManager.lookupInsecure}: the profile's skin once it is known, the default for its id until then. */
	public static Supplier<PlayerSkin> lookupInsecure(Minecraft minecraft, GameProfile profile) {
		PlayerSkin fallback = SkinBridge.byDefault(UUIDUtil.getOrCreatePlayerUUID(profile));
		CompletableFuture<PlayerSkin> loading = getOrLoad(minecraft, profile).exceptionally(error -> fallback);
		return () -> loading.getNow(fallback);
	}

	/**
	 * A player's profile by name, with its signed textures (what 1.20.2's {@code SkullBlockEntity.fetchGameProfile} finds; 1.19.2
	 * has its profile cache only while a world is open). Empty when no player has the name or Mojang can't be reached. Blocks: off
	 * the render thread.
	 */
	public static Optional<GameProfile> profileByName(Minecraft minecraft, String name) {
		GameProfile[] found = new GameProfile[1];
		new YggdrasilAuthenticationService(minecraft.getProxy()).createProfileRepository().findProfilesByNames(new String[] {name}, Agent.MINECRAFT,
			new ProfileLookupCallback() {
				@Override
				public void onProfileLookupSucceeded(GameProfile profile) {
					found[0] = profile;
				}

				@Override
				public void onProfileLookupFailed(GameProfile profile, Exception error) {
				}
			});
		GameProfile profile = found[0];
		return profile == null ? Optional.empty() : Optional.of(minecraft.getMinecraftSessionService().fillProfileProperties(profile, true));
	}

	private record Textures(Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> map, boolean secure) {
	}

	/** The profile's textures, checked against Mojang's signature when they carry one (1.20.2 notes which ones were signed). */
	private static Textures textures(Minecraft minecraft, GameProfile profile) {
		MinecraftSessionService sessions = minecraft.getMinecraftSessionService();
		Property property = Iterables.getFirst(profile.getProperties().get(SkinManager.PROPERTY_TEXTURES), null);
		boolean signed = property != null && property.hasSignature();
		try {
			return new Textures(sessions.getTextures(profile, signed), signed);
		} catch (InsecureTextureException e) {
			return new Textures(sessions.getTextures(profile, false), false);
		}
	}

	/** The textures registered with the skin manager (render thread), as the skin record. */
	private static PlayerSkin register(Minecraft minecraft, GameProfile profile, Textures textures) {
		UUID id = UUIDUtil.getOrCreatePlayerUUID(profile);
		MinecraftProfileTexture skin = textures.map().get(MinecraftProfileTexture.Type.SKIN);
		MinecraftProfileTexture cape = textures.map().get(MinecraftProfileTexture.Type.CAPE);
		MinecraftProfileTexture elytra = textures.map().get(MinecraftProfileTexture.Type.ELYTRA);
		if (skin == null && cape == null && elytra == null) {
			return SkinBridge.byDefault(id);
		}
		SkinManager skins = minecraft.getSkinManager();
		ResourceLocation texture = skin != null ? skins.registerTexture(skin, MinecraftProfileTexture.Type.SKIN) : DefaultPlayerSkin.getDefaultSkin(id);
		PlayerSkin.Model model = skin != null ? PlayerSkin.Model.byName(skin.getMetadata("model")) : SkinBridge.byDefault(id).model();
		return new PlayerSkin(texture, skin != null ? skin.getUrl() : null, registered(skins, cape, MinecraftProfileTexture.Type.CAPE),
			registered(skins, elytra, MinecraftProfileTexture.Type.ELYTRA), model, textures.secure());
	}

	private static @Nullable ResourceLocation registered(SkinManager skins, @Nullable MinecraftProfileTexture texture, MinecraftProfileTexture.Type type) {
		return texture == null ? null : skins.registerTexture(texture, type);
	}
}
