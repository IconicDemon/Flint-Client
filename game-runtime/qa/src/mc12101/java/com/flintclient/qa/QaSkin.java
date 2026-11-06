package com.flintclient.qa;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 1.21.1 (its PlayerSkin names textures by their path). A tour's own look for the QA player (offline, so it has only
 * a default skin and no cape): a built-in skin texture, its model and a cape texture, from
 * {@code call com.flintclient.qa.QaSkin.set entity/player/wide/steve wide flintqa:qa_cape} ("none" for no cape) until
 * {@code clear}. The test cape ships in this jar. Dev-only, like the rest of this mod.
 */
public final class QaSkin {
	private static final ResourceLocation CAPE = ResourceLocation.fromNamespaceAndPath("flintqa", "qa_cape");
	private static volatile @Nullable PlayerSkin skin;

	private QaSkin() {
	}

	/** A texture name as a profile gives it ("entity/player/wide/steve") as the texture's path. */
	private static ResourceLocation texturePath(ResourceLocation id) {
		return id.withPath(path -> "textures/" + path + ".png");
	}

	/** Registers the test cape ({@code flintqa:qa_cape}) with the game's textures. Client thread. */
	public static String loadCape() {
		ResourceLocation path = texturePath(CAPE);
		try (InputStream in = QaSkin.class.getResourceAsStream("/assets/flintqa/textures/qa_cape.png")) {
			if (in == null) {
				return "no cape in the QA jar";
			}
			NativeImage image = NativeImage.read(in);
			Minecraft.getInstance().getTextureManager().register(path, new DynamicTexture(image));
			return "registered " + path;
		} catch (Exception e) {
			return "failed: " + e;
		}
	}

	/** Body texture (as a profile names it, "entity/player/wide/steve"), "wide" or "slim", cape texture or "none". */
	public static String set(String body, String model, String cape) {
		if (cape.equals(CAPE.toString())) {
			loadCape();
		}
		ResourceLocation capeTexture = cape.equals("none") ? null : texturePath(ResourceLocation.parse(cape));
		skin = new PlayerSkin(texturePath(ResourceLocation.parse(body)), null, capeTexture, null,
			model.equals("slim") ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE, false);
		return String.valueOf(skin);
	}

	public static String clear() {
		skin = null;
		return "cleared";
	}

	/** The QA player's skin: the tour's when set, else Minecraft's. */
	public static PlayerSkin apply(PlayerSkin vanilla) {
		PlayerSkin own = skin;
		return own == null ? vanilla : own;
	}

	/** The stand-in for the QA player's Minecraft cape (Mojang's), on or off: see {@link #minecraft}. */
	private static volatile boolean minecraftCape;

	/**
	 * {@code on}: the QA player's skin carries the test cape as the cape Minecraft itself gave (an offline QA player can't
	 * have Mojang's), fed in under Flint's own Store cape hook ({@code mixin/PlayerInfoSkinMixin} runs before it), so a tour can
	 * check that a Flint Store cape takes its place and that it comes back when the Store cape is taken off. {@code off}: none.
	 */
	public static String minecraftCape(String on) {
		minecraftCape = on.equals("on");
		if (minecraftCape) {
			loadCape();
		}
		return "Minecraft cape stand-in " + (minecraftCape ? "on" : "off");
	}

	/** {@code info}'s skin as Minecraft gave it, with the stand-in Minecraft cape for the QA player while it is on. */
	public static PlayerSkin minecraft(PlayerInfo info, PlayerSkin vanilla) {
		Minecraft mc = Minecraft.getInstance();
		UUID self = mc.player == null ? null : mc.player.getUUID();
		if (!minecraftCape || self == null || !self.equals(info.getProfile().getId())) {
			return vanilla;
		}
		return new PlayerSkin(vanilla.texture(), vanilla.textureUrl(), texturePath(CAPE), vanilla.elytraTexture(), vanilla.model(), vanilla.secure());
	}

	/** Which cape the QA player's skin carries now (the texture's path, or none) and its own elytra texture. */
	public static String capeNow() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return "no player";
		}
		PlayerSkin skin = mc.player.getSkin();
		return "cape " + (skin.capeTexture() == null ? "none" : skin.capeTexture()) + ", elytra texture "
			+ (skin.elytraTexture() == null ? "none (the elytra wears the cape, or the default)" : skin.elytraTexture());
	}
}
