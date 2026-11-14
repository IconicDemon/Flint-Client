package com.flintclient.client.compat.render;

import com.flintclient.client.compat.PlayerSkin;
import java.util.UUID;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.2's {@code getSkin()} of PlayerInfo and AbstractClientPlayer on Minecraft 1.19.2, which keeps a player's skin, cape,
 * elytra and model apart: mixin.feature.PlayerInfoMixin and AbstractClientPlayerMixin answer it with Flint's stand-in for the
 * skin record ({@link PlayerSkin}), the one Flint Store capes and Nick Hider change. Loom can add this interface to both
 * classes at compile time (the shared sources then call {@code getSkin()} as they do on later versions); the mixins add it at
 * run time.
 */
public interface SkinBridge {
	/** This player's skin as you see it (with their Flint Store cape, or Nick Hider's default skin). */
	default PlayerSkin getSkin() {
		throw new IllegalStateException("PlayerInfoMixin or AbstractClientPlayerMixin isn't applied");
	}

	/** {@code skin}'s model as 1.19.2 names it ({@code "slim"} or {@code "default"}). */
	static String modelName(PlayerSkin skin) {
		return skin.model() == PlayerSkin.Model.SLIM ? "slim" : "default";
	}

	/** The model 1.19.2's name stands for (anything but {@code "slim"} is the wide one, as 1.19.2 draws it). */
	static PlayerSkin.Model model(String name) {
		return "slim".equals(name) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
	}

	/** 1.20.2's {@code DefaultPlayerSkin.get(id)}: the default skin 1.19.2 gives that player (Steve or Alex). */
	static PlayerSkin byDefault(UUID id) {
		return model(DefaultPlayerSkin.getSkinModelName(id)) == PlayerSkin.Model.SLIM ? Defaults.SLIM : Defaults.WIDE;
	}

	/** The two default skins, made once (a skin is compared by identity where it is kept, Mc263StoreCapes). */
	final class Defaults {
		static final PlayerSkin WIDE = make("default");
		static final PlayerSkin SLIM = make("slim");

		private Defaults() {
		}

		private static PlayerSkin make(String model) {
			// 1.19.2's default skin is picked from the id's hash: one of each kind.
			UUID id = findId(model);
			ResourceLocation texture = DefaultPlayerSkin.getDefaultSkin(id);
			return new PlayerSkin(texture, null, null, null, model(model), true);
		}

		private static UUID findId(String model) {
			for (long i = 0; ; i++) {
				UUID id = new UUID(0L, i);
				if (model.equals(DefaultPlayerSkin.getSkinModelName(id))) {
					return id;
				}
			}
		}
	}
}
