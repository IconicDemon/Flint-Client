package com.flintclient.qa.legacy;

import com.flintclient.qa.legacy.mixin.ClientPlayNetworkHandlerAccessor;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.RemoteClientPlayerEntity;
import net.minecraft.client.network.PlayerInfo;
import net.minecraft.entity.living.ArmorStandEntity;
import net.minecraft.server.entity.living.player.ServerPlayerEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;

/**
 * Other players for 1.8.9 nametag tours, without a second game: a remote player the way the client makes one when a server
 * says a player joined (a player-list entry, then the player entity), so it is drawn, named and asked about exactly as a real
 * one. {@code call com.flintclient.qa.legacy.QaBots.spawn NAME UUID MODE DX DZ}: MODE {@code listed} (in the Tab list) or
 * {@code noinfo} (no entry at all, as servers that drop the entry after spawning do); DX and DZ blocks from you (you face
 * south after {@code look 0 0}). Teams come from the server as usual. {@code ride TEXT} puts a named, invisible armour stand
 * on you in the integrated server, the way 1.8 servers hang their own name above a player. Fictional names only. Dev-only.
 */
public final class QaBots {
	private static final Map<UUID, Integer> BOTS = new LinkedHashMap<>();
	private static int nextId = 2_000_000;
	private static ArmorStandEntity stand;

	private QaBots() {
	}

	public static String spawn(String name, String uuid, String mode, int dx, int dz) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
			return "no world";
		}
		UUID id = UUID.fromString(uuid);
		GameProfile profile = new GameProfile(id, name);
		if (!mode.equals("noinfo")) {
			((ClientPlayNetworkHandlerAccessor) mc.getNetworkHandler()).flintqa$onlinePlayers().put(id, new PlayerInfo(profile));
		}
		RemoteClientPlayerEntity bot = new RemoteClientPlayerEntity(mc.world, profile);
		double x = Math.floor(mc.player.x) + 0.5 + dx;
		double z = Math.floor(mc.player.z) + 0.5 + dz;
		// Facing you (north, when you face south).
		bot.setPositionAndAngles(x, mc.player.y, z, 180, 0);
		bot.setHeadYaw(180);
		int entityId = nextId++;
		mc.world.forceEntity(entityId, bot);
		BOTS.put(id, entityId);
		return name + " " + id + " " + mode + " at " + x + " " + mc.player.y + " " + z;
	}

	public static String clear() {
		Minecraft mc = Minecraft.getInstance();
		List<String> gone = new ArrayList<>();
		for (Map.Entry<UUID, Integer> bot : BOTS.entrySet()) {
			if (mc.world != null) {
				mc.world.removeEntity(bot.getValue());
			}
			if (mc.getNetworkHandler() != null) {
				((ClientPlayNetworkHandlerAccessor) mc.getNetworkHandler()).flintqa$onlinePlayers().remove(bot.getKey());
			}
			gone.add(bot.getKey().toString());
		}
		BOTS.clear();
		return "removed " + gone;
	}

	/** A named, invisible armour stand riding you in the integrated server (as 1.8 servers draw a name above a player). */
	public static String ride(String text) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getServer();
		UUID you = mc.player.getUuid();
		String name = text.replace('_', ' ').replace('&', '§');
		return String.valueOf(server.executeTask(() -> {
			ServerPlayerEntity player = server.getPlayerManager().get(you);
			ServerWorld world = server.getWorld(0);
			ArmorStandEntity armorStand = new ArmorStandEntity(world, player.x, player.y + 2, player.z);
			armorStand.setInvisible(true);
			armorStand.setCustomName(name);
			armorStand.setCustomNameVisible(true);
			world.addEntity(armorStand);
			armorStand.startRiding(player);
			stand = armorStand;
			return "armour stand " + name + " riding " + player.getName();
		}).get());
	}

	/** Takes the armour stand off you again. */
	public static String unride() throws Exception {
		IntegratedServer server = Minecraft.getInstance().getServer();
		return String.valueOf(server.executeTask(() -> {
			ArmorStandEntity armorStand = stand;
			stand = null;
			if (armorStand == null) {
				return "nothing riding";
			}
			armorStand.startRiding(null);
			armorStand.remove();
			return "armour stand removed";
		}).get());
	}

	/** Flint's "Flint icon on nametags" option, on or off, as the menu sets it. */
	public static String badge(String on) throws ReflectiveOperationException {
		Object settings = Class.forName("com.flintclient.client.control.ControlState").getMethod("settings").invoke(null);
		Object option = Class.forName("com.flintclient.client.control.model.GameOptions").getField("FLINT_BADGE").get(null);
		Class<?> descriptor = Class.forName("com.flintclient.client.control.model.SettingDescriptor");
		Object changed = settings.getClass().getMethod("setGame", descriptor, Object.class).invoke(settings, option, Boolean.valueOf(on.equals("on")));
		return "Flint icon on nametags " + on + " (changed: " + changed + ")";
	}

	/** Which players Flint knows to be on Flint now, with their marks (FlintPlayers). */
	public static String known() throws ReflectiveOperationException {
		Class<?> players = Class.forName("com.flintclient.client.control.feature.FlintPlayers");
		StringBuilder out = new StringBuilder();
		for (UUID id : BOTS.keySet()) {
			out.append(id).append('=').append(players.getMethod("tag", UUID.class).invoke(null, id)).append(' ');
		}
		return out.append("own=").append(players.getMethod("own").invoke(null)).toString();
	}
}
