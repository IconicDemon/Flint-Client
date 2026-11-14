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
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Minecraft 1.21.1 (authlib 6 profiles, textures by ResourceLocation). The Capes mod (CaelTheColher's "Capes": OptiFine, LabyMod and MinecraftCapes capes) in a tour, without its services: a remote
 * player whose profile the Capes mod takes as a real one (a version 4 id and a {@code textures} property naming the player, as
 * Mojang's session server gives), and that mod's own state for them set as if it had downloaded a cape: a lime test texture at
 * its own address ({@code capes:<uuid>}), {@code hasCape} on, nothing more to fetch. So the Capes mod's real mixin
 * ({@code PlayerInfo.getSkin}) hands out its cape exactly as with a real download, and the tour sees which cape wins.
 * Reflection only: the QA mod doesn't build against it. Dev-only.
 *
 * <ul>
 * <li>{@code call com.flintclient.qa.QaCapesMod.spawn NAME uuid:UUID DX DZ YAW elytra|none}: the player, YAW 0 to show its back
 * when you face south ({@code command tp @s ~ ~ ~ 0 0}).</li>
 * <li>{@code call com.flintclient.qa.QaCapesMod.capeOf uuid:UUID}: logs the cape and elytra textures its skin carries.</li>
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
			// Nothing to download for them (the Capes mod's 26.x and 1.21.9+ builds; 1.21.1's asks each time and finds nothing).
			handlerClass.getMethod("setHasLoadedTextures", boolean.class).invoke(handler, true);
		} catch (NoSuchMethodException older) {
			// fetched in the background; its answer for a made-up player is "no cape", which changes nothing
		}
		handlerClass.getMethod("setHasElytraTexture", boolean.class).invoke(handler, true);
		NativeImage image = new NativeImage(64, 32, true);
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 64; x++) {
				// Lime with a dark diagonal stripe: unlike the Store's test cape and the QA "Minecraft" cape.
				boolean stripe = Math.floorMod(x - y, 8) < 2;
				image.setPixelRGBA(x, y, stripe ? 0xFF0B4F0B : 0xFF32FF32);
			}
		}
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("capes", profile.getId().toString());
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
		PlayerInfo info = new PlayerInfo(profile, false);
		connection.flintqa$playerInfoMap().put(id, info);
		connection.flintqa$listedPlayers().add(info);
		RemotePlayer bot = new RemotePlayer(level, profile);
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
		level.addEntity(bot);
		BOTS.put(id, entityId);
		return name + " " + id + " at " + x + " " + z + "; " + cape;
	}

	/** {@code id}'s skin as Minecraft draws it now: its cape and elytra textures. */
	public static String capeOf(UUID id) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null) {
			return "no world";
		}
		PlayerInfo info = mc.getConnection().getPlayerInfo(id);
		if (info == null) {
			return "no player " + id;
		}
		PlayerSkin skin = info.getSkin();
		return id + " cape " + (skin.capeTexture() == null ? "none" : skin.capeTexture()) + ", elytra texture "
			+ (skin.elytraTexture() == null ? "none" : skin.elytraTexture());
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
