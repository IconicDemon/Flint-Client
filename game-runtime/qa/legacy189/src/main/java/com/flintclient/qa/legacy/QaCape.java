package com.flintclient.qa.legacy;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.PlayerInfo;
import net.minecraft.client.render.texture.DynamicTexture;
import net.minecraft.resource.Identifier;

/**
 * Minecraft 1.8.9: a stand-in for the QA player's Minecraft cape (Mojang's, which an offline QA player can't have), on or off
 * with {@code call com.flintclient.qa.legacy.QaCape.minecraftCape on|off}. It is what the player's tab-list entry gives as the
 * cape ({@code mixin/PlayerInfoMixin}), underneath Flint's own Store cape hook on the player, so a tour can check that a Flint
 * Store cape takes its place and that it comes back when the Store cape is taken off. Dev-only, like the rest of this mod.
 */
public final class QaCape {
	private static final Identifier CAPE = new Identifier("flintqa", "minecraft_cape_stand_in");
	private static volatile boolean on;
	private static boolean registered;

	private QaCape() {
	}

	public static String minecraftCape(String state) {
		on = state.equals("on");
		if (on && !registered) {
			// Mojang's 64x32 cape layout: a blue outside (x 1..10, y 1..16) with white bands and pale edges, so it can't be taken
			// for a Flint test cape.
			DynamicTexture texture = new DynamicTexture(64, 32);
			int[] px = texture.getPixels();
			for (int y = 0; y < 32; y++) {
				for (int x = 0; x < 64; x++) {
					boolean cape = x <= 21 && y <= 16;
					boolean edge = y == 0 || x == 0 || x == 11;
					boolean band = (y - 3) % 4 < 2 && y >= 3;
					px[y * 64 + x] = !cape ? 0 : edge ? 0xFFCFD8E6 : band ? 0xFFFFFFFF : 0xFF1F4F8F;
				}
			}
			texture.upload();
			Minecraft.getInstance().getTextureManager().register(CAPE, texture);
			registered = true;
		}
		return "Minecraft cape stand-in " + (on ? "on" : "off");
	}

	/** The cape {@code info} gives (Minecraft's), or the stand-in for the QA player while it is on. */
	public static Identifier minecraft(PlayerInfo info, Identifier vanilla) {
		Minecraft mc = Minecraft.getInstance();
		UUID self = mc.player == null ? null : mc.player.getUuid();
		return on && self != null && self.equals(info.getProfile().getId()) ? CAPE : vanilla;
	}

	/** Which cape the QA player's cape layer binds now: the texture's name, or none. */
	public static String capeNow() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return "no player";
		}
		Identifier cape = mc.player.getCapeTextureLocation();
		return "cape " + (cape == null ? "none" : cape.toString());
	}
}
