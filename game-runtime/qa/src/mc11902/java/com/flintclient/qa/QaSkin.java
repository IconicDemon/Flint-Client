package com.flintclient.qa;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 1.19.2 (and 1.19), which has no skin record: a player's skin, cape, elytra and model are what its player info
 * keeps (the textures Mojang's skin service hands it, and the model's name), and everything that draws a player reads them
 * from there, Flint too (its Store cape and Nick Hider start from them). So the tour's look for the QA player is written into
 * them, as if Mojang had sent it (mixin/PlayerInfoSkinMixin), and Minecraft's own are put back when the tour is done. Unlike on
 * later versions, where the tour's skin replaces the finished one, a Flint Store cape or Nick Hider still shows over it here.
 *
 * <p>A tour's own look for the QA player (offline, so it has only a default skin and no cape): a built-in skin texture, its
 * model and a cape texture, from {@code call com.flintclient.qa.QaSkin.set entity/player/wide/steve wide flintqa:qa_cape}
 * ("none" for no cape) until {@code clear}. 1.19.2 has two default skins (textures/entity/steve.png and alex.png): later
 * versions' default skin names (entity/player/wide/NAME, entity/player/slim/NAME) become Steve or Alex for their model. The
 * test cape ships in this jar. Dev-only, like the rest of this mod.
 */
public final class QaSkin {
	private static final ResourceLocation CAPE = new ResourceLocation("flintqa", "qa_cape");
	private static final ResourceLocation CAPE_TEXTURE = texturePath(CAPE);
	private static final MinecraftProfileTexture.Type[] TYPES = MinecraftProfileTexture.Type.values();
	/** The tour's skin (set), or null. */
	private static volatile @Nullable Look skin;
	/** The stand-in for the QA player's Minecraft cape (Mojang's), on or off: see {@link #minecraftCape}. */
	private static volatile boolean minecraftCape;
	/** What the QA player's player info is given now (the tour's skin, else the cape stand-in), or null for Minecraft's own. */
	private static volatile @Nullable Look current;

	private QaSkin() {
	}

	/**
	 * A look written into a player info: the whole skin (texture, cape or none, no elytra texture, model), or only the cape
	 * over Minecraft's own skin.
	 */
	private record Look(boolean whole, @Nullable ResourceLocation body, @Nullable ResourceLocation cape, @Nullable String model) {
		@Nullable ResourceLocation texture(MinecraftProfileTexture.Type type, @Nullable ResourceLocation own) {
			if (type == MinecraftProfileTexture.Type.CAPE) {
				return cape;
			}
			if (!whole) {
				return own;
			}
			return type == MinecraftProfileTexture.Type.SKIN ? body : null;
		}

		@Nullable String model(@Nullable String own) {
			return whole ? model : own;
		}
	}

	/** A texture name as a profile gives it ("entity/player/wide/steve") as the texture's path. */
	private static ResourceLocation texturePath(ResourceLocation id) {
		return new ResourceLocation(id.getNamespace(), "textures/" + id.getPath() + ".png");
	}

	/** Later versions' default skins (entity/player/wide/NAME, entity/player/slim/NAME) as 1.19.2's: Steve or Alex. */
	private static ResourceLocation body(String body, boolean slim) {
		ResourceLocation id = new ResourceLocation(body);
		if (id.getNamespace().equals("minecraft") && id.getPath().startsWith("entity/player/")) {
			boolean alex = id.getPath().endsWith("/alex") || (slim && !id.getPath().endsWith("/steve"));
			return texturePath(new ResourceLocation(alex ? "entity/alex" : "entity/steve"));
		}
		return texturePath(id);
	}

	/** Registers the test cape ({@code flintqa:qa_cape}) with the game's textures. Client thread. */
	public static String loadCape() {
		try (InputStream in = QaSkin.class.getResourceAsStream("/assets/flintqa/textures/qa_cape.png")) {
			if (in == null) {
				return "no cape in the QA jar";
			}
			NativeImage image = NativeImage.read(in);
			Minecraft.getInstance().getTextureManager().register(CAPE_TEXTURE, new DynamicTexture(image));
			return "registered " + CAPE_TEXTURE;
		} catch (Exception e) {
			return "failed: " + e;
		}
	}

	/** Body texture (as a profile names it, "entity/player/wide/steve"), "wide" or "slim", cape texture or "none". */
	public static String set(String body, String model, String cape) {
		if (cape.equals(CAPE.toString())) {
			loadCape();
		}
		boolean slim = model.equals("slim");
		ResourceLocation capeTexture = cape.equals("none") ? null : texturePath(new ResourceLocation(cape));
		Look look = new Look(true, body(body, slim), capeTexture, slim ? "slim" : "default");
		skin = look;
		update();
		return String.valueOf(look);
	}

	public static String clear() {
		skin = null;
		update();
		return "cleared";
	}

	/**
	 * {@code on}: the QA player's skin carries the test cape as the cape Minecraft itself gave (an offline QA player can't
	 * have Mojang's), written where Minecraft keeps Mojang's, under Flint's own Store cape hook, so a tour can check that a
	 * Flint Store cape takes its place and that it comes back when the Store cape is taken off. {@code off}: none.
	 */
	public static String minecraftCape(String on) {
		minecraftCape = on.equals("on");
		if (minecraftCape) {
			loadCape();
		}
		update();
		return "Minecraft cape stand-in " + (minecraftCape ? "on" : "off");
	}

	private static void update() {
		Look own = skin;
		current = own != null ? own : minecraftCape ? new Look(false, null, CAPE_TEXTURE, null) : null;
	}

	/** Whether the tour gives the QA player a look now (PlayerInfoSkinMixin skips every player info until it does). */
	public static boolean active() {
		return current != null;
	}

	/** What the tour wrote into one player info, and Minecraft's own textures and model under it (PlayerInfoSkinMixin keeps one each). */
	public static final class Written {
		private final @Nullable ResourceLocation[] own = new ResourceLocation[TYPES.length];
		private final @Nullable ResourceLocation[] wrote = new ResourceLocation[TYPES.length];
		private @Nullable String ownModel;
		private @Nullable String wroteModel;
		private boolean on;
	}

	/**
	 * From PlayerInfoSkinMixin, each time player {@code id}'s info has registered its textures (Minecraft does before every
	 * read): the tour's look written into them for the QA player, or Minecraft's own put back. Returns the model's name the
	 * player info keeps.
	 */
	public static @Nullable String write(UUID id, Map<MinecraftProfileTexture.Type, ResourceLocation> textures, @Nullable String model, Written written) {
		Minecraft mc = Minecraft.getInstance();
		Look look = mc.player != null && id.equals(mc.player.getUUID()) ? current : null;
		if (!written.on) {
			if (look == null) {
				return model;
			}
			for (MinecraftProfileTexture.Type type : TYPES) {
				written.own[type.ordinal()] = textures.get(type);
			}
			written.ownModel = model;
		} else {
			// What Minecraft's skin service filled in since is Minecraft's own now.
			for (MinecraftProfileTexture.Type type : TYPES) {
				ResourceLocation now = textures.get(type);
				if (now != written.wrote[type.ordinal()]) {
					written.own[type.ordinal()] = now;
				}
			}
			if (model != written.wroteModel) {
				written.ownModel = model;
			}
		}
		for (MinecraftProfileTexture.Type type : TYPES) {
			int i = type.ordinal();
			ResourceLocation texture = look == null ? written.own[i] : look.texture(type, written.own[i]);
			if (textures.get(type) != texture) {
				if (texture == null) {
					textures.remove(type);
				} else {
					textures.put(type, texture);
				}
			}
			written.wrote[i] = texture;
		}
		String result = look == null ? written.ownModel : look.model(written.ownModel);
		written.wroteModel = result;
		written.on = look != null;
		return result;
	}

	/** Which cape the QA player's skin carries now (the texture's path, or none) and its own elytra texture. */
	public static String capeNow() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return "no player";
		}
		ResourceLocation cape = mc.player.getCloakTextureLocation();
		ResourceLocation elytra = mc.player.getElytraTextureLocation();
		return "cape " + (cape == null ? "none" : cape) + ", elytra texture " + (elytra == null ? "none (the elytra wears the cape, or the default)" : elytra);
	}
}
