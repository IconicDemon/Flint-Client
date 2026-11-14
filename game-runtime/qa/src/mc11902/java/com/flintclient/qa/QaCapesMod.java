package com.flintclient.qa;

import com.flintclient.qa.mixin.ClientPacketListenerAccessor;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Minecraft 1.19.x (authlib 3 profiles, textures by ResourceLocation, a player's textures behind PlayerInfo's getters). The
 * Capes mod (CaelTheColher's "Capes") in a tour, without its services, as the 1.21.1 version does: a remote player whose
 * profile the Capes mod takes as a real one, and that mod's own state for them set as if it had downloaded a cape (a lime test
 * texture at {@code capes:<uuid>}). Reflection only, and only where the Capes mod's 1.19 builds have the same handler. Dev-only.
 *
 * <ul>
 * <li>{@code call com.flintclient.qa.QaCapesMod.spawn NAME uuid:UUID DX DZ YAW elytra|none}: the player.</li>
 * <li>{@code call com.flintclient.qa.QaCapesMod.capeOf uuid:UUID}: logs the cape and elytra textures it carries.</li>
 * <li>{@code call com.flintclient.qa.QaCapesMod.loaded}: whether the Capes mod is in this game.</li>
 * </ul>
 */
public final class QaCapesMod {
	private static final Map<UUID, Integer> BOTS = new LinkedHashMap<>();
	private static int nextId = 2_100_000;

	private QaCapesMod() {
	}

	public static String loaded() {
		try {
			Class.forName("me.cael.capes.handler.PlayerHandler");
			return "the Capes mod is loaded";
		} catch (ClassNotFoundException e) {
			return "no Capes mod";
		}
	}

	/** The Capes mod's handler for {@code profile}, set as if it had downloaded a cape for them; the texture registered. */
	private static String giveCape(GameProfile profile) throws ReflectiveOperationException {
		Class<?> handlerClass = Class.forName("me.cael.capes.handler.PlayerHandler");
		Object companion = handlerClass.getField("Companion").get(null);
		Object handler = companion.getClass().getMethod("fromProfile", GameProfile.class).invoke(companion, profile);
		try {
			handlerClass.getMethod("setHasLoadedTextures", boolean.class).invoke(handler, true);
		} catch (NoSuchMethodException older) {
			// fetched in the background; its answer for a made-up player is "no cape", which changes nothing
		}
		handlerClass.getMethod("setHasElytraTexture", boolean.class).invoke(handler, true);
		NativeImage image = new NativeImage(64, 32, true);
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 64; x++) {
				// Lime with a dark diagonal stripe (NativeImage keeps 0xAABBGGRR): unlike the Store's test cape and the QA "Minecraft" cape.
				boolean stripe = Math.floorMod(x - y, 8) < 2;
				image.setPixelRGBA(x, y, stripe ? 0xFF0B4F0B : 0xFF32FF32);
			}
		}
		ResourceLocation id = new ResourceLocation("capes", profile.getId().toString());
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
		handlerClass.getMethod("setHasCape", boolean.class).invoke(handler, true);
		return "Capes mod cape " + id;
	}

	public static String spawn(String name, UUID id, int dx, int dz, int yaw, String elytra) throws ReflectiveOperationException {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer you = mc.player;
		ClientLevel level = mc.level;
		if (you == null || level == null || mc.getConnection() == null) {
			return "no world";
		}
		String json = "{\"timestamp\":" + System.currentTimeMillis() + ",\"profileId\":\"" + id.toString().replace("-", "")
			+ "\",\"profileName\":\"" + name + "\",\"textures\":{}}";
		String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		GameProfile profile = new GameProfile(id, name);
		profile.getProperties().put("textures", new Property("textures", value));
		String cape = loaded().startsWith("the") ? giveCape(profile) : "no Capes mod";
		ClientPacketListenerAccessor connection = (ClientPacketListenerAccessor) mc.getConnection();
		PlayerInfo info = QaChat.playerInfo(mc, profile);
		connection.flintqa$playerInfoMap().put(id, info);
		RemotePlayer bot = new RemotePlayer(level, profile, null);
		int entityId = nextId++;
		bot.setId(entityId);
		double x = Math.floor(you.getX()) + 0.5 + dx;
		double z = Math.floor(you.getZ()) + 0.5 + dz;
		bot.setPos(x, you.getY(), z);
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.yBodyRot = yaw;
		bot.yBodyRotO = yaw;
		bot.yHeadRotO = yaw;
		bot.setOldPosAndRot();
		// Every skin part shown (the cape included), as a server says for a player with Minecraft's defaults (QaElytra does the same).
		for (Class<?> c = bot.getClass(); c != null; c = c.getSuperclass()) {
			try {
				java.lang.reflect.Field field = QaNames.field(c, "DATA_PLAYER_MODE_CUSTOMISATION");
				@SuppressWarnings("unchecked")
				net.minecraft.network.syncher.EntityDataAccessor<Byte> parts = (net.minecraft.network.syncher.EntityDataAccessor<Byte>) field.get(null);
				bot.getEntityData().set(parts, (byte) 0x7F);
				break;
			} catch (NoSuchFieldException e) {
				// declared on a superclass
			}
		}
		if (elytra.equals("elytra")) {
			bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
		}
		level.addPlayer(entityId, bot);
		BOTS.put(id, entityId);
		return name + " " + id + " at " + x + " " + z + "; " + cape;
	}

	/** {@code id}'s cape and elytra textures as Minecraft draws them now (1.19.x asks PlayerInfo's getters). */
	public static String capeOf(UUID id) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null) {
			return "no world";
		}
		PlayerInfo info = mc.getConnection().getPlayerInfo(id);
		if (info == null) {
			return "no player " + id;
		}
		ResourceLocation cape = info.getCapeLocation();
		ResourceLocation wings = info.getElytraLocation();
		return id + " cape " + (cape == null ? "none" : cape) + ", elytra texture " + (wings == null ? "none" : wings);
	}

	public static String clear() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			for (int entityId : BOTS.values()) {
				mc.level.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
			}
		}
		BOTS.clear();
		return "removed";
	}
}
