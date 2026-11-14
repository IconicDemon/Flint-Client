package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.PlayerSkin;
import com.flintclient.client.compat.render.SkinBridge;
import com.flintclient.client.control.platform.Mc263Overlay;
import com.flintclient.client.control.platform.Mc263StoreCapes;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * A player's skin on your screen: the cape they equipped in the Flint Store, if any (control/platform/Mc263StoreCapes), then
 * Nick Hider (a default skin, on your screen only, for you and/or others; a hidden skin shows no Store cape either).
 *
 * <p>Minecraft 1.19.x has no skin record: a player's skin, cape, elytra and model are four getters here, which everything that
 * draws a player reads (the renderer, the cape and elytra layers, the tab list), and which cape mods hook (the Capes mod puts its
 * OptiFine, LabyMod or MinecraftCapes cape in with a cancellable injection at the end of {@code getCapeLocation}, which returns
 * at once). Flint decides last, as on later versions: each getter is wrapped whole ({@code @WrapMethod} goes around every other
 * mod's injections into it, and this mixin's priority puts it outside other wrappers too), so Flint sees what the mods answered.
 * Those answers over Minecraft's own skin make the skin the mods made (kept, and remade only when a part changes, so drawing
 * allocates nothing); it goes through the same two steps as on later versions ({@link SkinBridge#getSkin}), and each getter
 * answers from what came out. A player wearing a Flint Store cape gets that cape (another mod's cape for them is hidden, and so
 * is any mod's elytra texture: their wings are Minecraft's); a player without one keeps the mods' answers, so the Capes mod's
 * cape shows, and shows again once a Store cape comes off. The elytra layer's own cape-mod switch is WingsLayerMixin's.
 */
@Mixin(value = PlayerInfo.class, priority = 1100)
abstract class PlayerInfoMixin implements SkinBridge {
	@Unique
	private static final int TEXTURE = 1;
	@Unique
	private static final int CAPE = 2;
	@Unique
	private static final int ELYTRA = 4;
	@Unique
	private static final int MODEL = 8;

	@Shadow
	@Final
	private GameProfile profile;
	@Shadow
	@Final
	private Map<MinecraftProfileTexture.Type, ResourceLocation> textureLocations;
	@Shadow
	private @Nullable String skinModel;

	/** Minecraft's skin for this player as its getters would answer without any mod, kept until one of its parts changes. */
	@Unique
	private @Nullable PlayerSkin flint$vanilla;
	/** Minecraft's skin with the parts the mods answered, kept the same way (Minecraft's own while they changed nothing). */
	@Unique
	private @Nullable PlayerSkin flint$modded;
	/** What each getter answered with every other mod's hooks, the last time it was asked (the bits: those asked so far). */
	@Unique
	private int flint$answered;
	@Unique
	private @Nullable ResourceLocation flint$modTexture;
	@Unique
	private @Nullable ResourceLocation flint$modCape;
	@Unique
	private @Nullable ResourceLocation flint$modElytra;
	@Unique
	private PlayerSkin.@Nullable Model flint$modModel;

	@Shadow
	protected abstract void registerTextures();

	@Unique
	private PlayerSkin flint$vanilla() {
		registerTextures();
		UUID id = profile.getId();
		ResourceLocation texture = textureLocations.get(MinecraftProfileTexture.Type.SKIN);
		if (texture == null) {
			texture = DefaultPlayerSkin.getDefaultSkin(id);
		}
		ResourceLocation cape = textureLocations.get(MinecraftProfileTexture.Type.CAPE);
		ResourceLocation elytra = textureLocations.get(MinecraftProfileTexture.Type.ELYTRA);
		PlayerSkin.Model model = SkinBridge.model(skinModel == null ? DefaultPlayerSkin.getSkinModelName(id) : skinModel);
		PlayerSkin skin = flint$vanilla;
		if (skin == null || skin.texture() != texture || skin.capeTexture() != cape || skin.elytraTexture() != elytra || skin.model() != model) {
			skin = new PlayerSkin(texture, null, cape, elytra, model, true);
			flint$vanilla = skin;
		}
		return skin;
	}

	/** {@code vanilla} with the parts the mods answered (Minecraft's own where a getter wasn't asked yet). */
	@Unique
	private PlayerSkin flint$modded(PlayerSkin vanilla) {
		int answered = flint$answered;
		ResourceLocation texture = (answered & TEXTURE) != 0 && flint$modTexture != null ? flint$modTexture : vanilla.texture();
		ResourceLocation cape = (answered & CAPE) != 0 ? flint$modCape : vanilla.capeTexture();
		ResourceLocation elytra = (answered & ELYTRA) != 0 ? flint$modElytra : vanilla.elytraTexture();
		PlayerSkin.Model model = (answered & MODEL) != 0 && flint$modModel != null ? flint$modModel : vanilla.model();
		if (texture == vanilla.texture() && cape == vanilla.capeTexture() && elytra == vanilla.elytraTexture() && model == vanilla.model()) {
			return vanilla;
		}
		PlayerSkin skin = flint$modded;
		if (skin == null || skin.texture() != texture || skin.capeTexture() != cape || skin.elytraTexture() != elytra || skin.model() != model) {
			skin = new PlayerSkin(texture, null, cape, elytra, model, true);
			flint$modded = skin;
		}
		return skin;
	}

	@Override
	public PlayerSkin getSkin() {
		PlayerInfo info = (PlayerInfo) (Object) this;
		PlayerSkin vanilla = flint$vanilla();
		// The skin just applied in the wardrobe first (compat/OwnSkin), then the Store cape and Nick Hider on top, as on 26.3.
		return Mc263Overlay.skin(info, Mc263StoreCapes.skin(info, com.flintclient.client.compat.OwnSkin.apply(info, flint$modded(vanilla)), vanilla));
	}

	@WrapMethod(method = "getSkinLocation")
	private ResourceLocation flint$skinTexture(Operation<ResourceLocation> original) {
		flint$modTexture = original.call();
		flint$answered |= TEXTURE;
		return getSkin().texture();
	}

	@WrapMethod(method = "getCapeLocation")
	private @Nullable ResourceLocation flint$capeTexture(Operation<ResourceLocation> original) {
		flint$modCape = original.call();
		flint$answered |= CAPE;
		return getSkin().capeTexture();
	}

	@WrapMethod(method = "getElytraLocation")
	private @Nullable ResourceLocation flint$elytraTexture(Operation<ResourceLocation> original) {
		flint$modElytra = original.call();
		flint$answered |= ELYTRA;
		return getSkin().elytraTexture();
	}

	@WrapMethod(method = "getModelName")
	private String flint$model(Operation<String> original) {
		String model = original.call();
		flint$modModel = model == null ? null : SkinBridge.model(model);
		flint$answered |= MODEL;
		return SkinBridge.modelName(getSkin());
	}
}
