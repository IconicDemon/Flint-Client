package com.flintclient.client.compat;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * The skin the player just applied from Flint's wardrobe, on their own player at once (first person, third person, F5),
 * without rejoining: Minecraft keeps the skin it looked up when the player joined until the next join, so until then the
 * player's own entry wears the uploaded picture ({@code mixin/feature/OwnSkinMixin} on {@code PlayerInfo.getSkin}). Only the
 * account the game plays as now, and only this session; other players see it once their game looks the player up again.
 * The cape, the elytra and Nick Hider stay as they were (they apply on top). On 26.1 as on 26.3.
 */
public final class OwnSkin {
	private static volatile @Nullable Identifier texture;
	private static volatile boolean slim;
	private static volatile @Nullable UUID owner;
	/** The last skin given and the one made from it (the same skin object every frame, as Minecraft's own). */
	private static @Nullable PlayerSkin lastIn;
	private static @Nullable PlayerSkin lastOut;

	private OwnSkin() {
	}

	/** Wears {@code skin} (a 64 × 64 texture of Flint's) as {@code owner}'s, with the slim or classic arm model. */
	public static void wear(Identifier skin, boolean slimArms, UUID account) {
		texture = skin;
		slim = slimArms;
		owner = account;
		lastIn = null;
		lastOut = null;
	}

	/** {@code skin} as Minecraft gives it for {@code info}: the applied one instead for the player's own entry. */
	public static PlayerSkin apply(PlayerInfo info, PlayerSkin skin) {
		Identifier t = texture;
		UUID o = owner;
		if (t == null || o == null || !o.equals(info.getProfile().id()) || !o.equals(Minecraft.getInstance().getUser().getProfileId())) {
			return skin;
		}
		PlayerSkin in = lastIn;
		PlayerSkin out = lastOut;
		if (in == skin && out != null) {
			return out;
		}
		out = new PlayerSkin(new ClientAsset.ResourceTexture(t, t), skin.cape(), skin.elytra(), slim ? PlayerModelType.SLIM : PlayerModelType.WIDE,
			skin.secure());
		lastIn = skin;
		lastOut = out;
		return out;
	}
}
