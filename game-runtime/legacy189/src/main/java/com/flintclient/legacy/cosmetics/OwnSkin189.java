package com.flintclient.legacy.cosmetics;

import com.flintclient.client.platform.Texture;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.client.render.texture.AbstractTexture;
import net.minecraft.client.resource.manager.ResourceManager;
import net.minecraft.resource.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The wardrobe's Apply on 1.8.9 (26.3's {@code OwnSkin}): the skin just uploaded on your own player at once, in the world and
 * in the inventory, with its arm model, until the next join looks your skin up anew ({@code mixin/cosmetics/OwnSkinMixin}
 * answers your player's skin and model). The texture is the wardrobe's own, registered with Minecraft's texture manager
 * under one name. Render thread.
 */
public final class OwnSkin189 {
	private static final Identifier ID = new Identifier("flintclient", "own_skin");
	private static volatile @Nullable UUID wearer;
	private static volatile boolean slim;
	private static @Nullable Object worldAt;

	private OwnSkin189() {
	}

	/** Wears {@code skin} ({@code slim} arms) on the signed-in player now. False without a signed-in player. */
	public static boolean wear(Texture skin, boolean slimArms) {
		Minecraft mc = Minecraft.getInstance();
		UUID me = mc.getSession() == null || mc.getSession().getProfile() == null ? null : mc.getSession().getProfile().getId();
		if (me == null) {
			return false;
		}
		int glId = skin.glId();
		mc.getTextureManager().register(ID, new AbstractTexture() {
			@Override
			public void load(ResourceManager resourceManager) {
			}

			@Override
			public int getGlId() {
				return glId;
			}

			@Override
			public void clearGlId() {
				// The wardrobe owns the texture.
			}
		});
		slim = slimArms;
		wearer = me;
		worldAt = mc.world;
		return true;
	}

	/** The skin {@code player} wears now, or null (theirs as Minecraft knows it). */
	public static @Nullable Identifier skin(ClientPlayerEntity player) {
		return mine(player) ? ID : null;
	}

	/** The arm model {@code player} has now ("slim" / "default"), or null. */
	public static @Nullable String model(ClientPlayerEntity player) {
		return mine(player) ? slim ? "slim" : "default" : null;
	}

	private static boolean mine(ClientPlayerEntity player) {
		UUID w = wearer;
		if (w == null || !w.equals(player.getUuid())) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (worldAt != null && mc.world != worldAt) {
			// The next join looks your skin up anew (the Store has it by then).
			wearer = null;
			return false;
		}
		return true;
	}
}
