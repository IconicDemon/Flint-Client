package com.flintclient.client.compat;

import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * 1.20.2's {@code PlayerSkin} on Minecraft 1.19.2, which keeps a player's skin, cape, elytra and model apart (PlayerInfo's
 * getters): the same record, made from those by mixin.feature.PlayerInfoMixin and handed out by compat.render.SkinBridge.
 */
public record PlayerSkin(ResourceLocation texture, @Nullable String textureUrl, @Nullable ResourceLocation capeTexture,
	@Nullable ResourceLocation elytraTexture, Model model, boolean secure) {
	public enum Model {
		SLIM("slim"),
		WIDE("default");

		private final String id;

		Model(String id) {
			this.id = id;
		}

		/** The model by 1.19.2's name for it (anything but {@code "slim"} is the wide one). */
		public static Model byName(@Nullable String name) {
			return "slim".equals(name) ? SLIM : WIDE;
		}

		public String id() {
			return id;
		}
	}
}
