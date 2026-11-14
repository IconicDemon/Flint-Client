package com.flintclient.qa;

import com.flintclient.qa.mixin.ClientPacketListenerAccessor;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Other players for nametag tours, without a second game: a remote player the way the client makes one when a server says
 * a player joined (a player-list entry, then the player entity), so it is drawn, named and asked about exactly as a real
 * one. {@code call com.flintclient.qa.QaBots.spawn NAME uuid:UUID MODE DX DZ}: MODE {@code listed} (in the Tab list),
 * {@code unlisted} or {@code noinfo} (no entry at all, as servers that drop the entry after spawning do); DX and DZ blocks
 * from you (you face south after {@code command tp @s ~ ~ ~ 0 0}). Minecraft 1.19.2 lists every player it knows (unlisted
 * entries came in 1.19.3), so {@code unlisted} is a listed entry here. Teams come from the server as usual
 * ({@code command team join T NAME}). The server never hears of these players; {@code clear} removes them. Fictional names
 * only. Dev-only.
 */
public final class QaBots {
	private static final Map<UUID, Integer> BOTS = new LinkedHashMap<>();
	private static int nextId = 2_000_000;

	private QaBots() {
	}

	public static String spawn(String name, UUID id, String mode, int dx, int dz) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer you = mc.player;
		ClientLevel level = mc.level;
		if (you == null || level == null || mc.getConnection() == null) {
			return "no world";
		}
		GameProfile profile = new GameProfile(id, name);
		ClientPacketListenerAccessor connection = (ClientPacketListenerAccessor) mc.getConnection();
		if (!mode.equals("noinfo")) {
			connection.flintqa$playerInfoMap().put(id, QaChat.playerInfo(mc, profile));
		}
		RemotePlayer bot = new RemotePlayer(level, profile, null);
		int entityId = nextId++;
		bot.setId(entityId);
		double x = Math.floor(you.getX()) + 0.5 + dx;
		double z = Math.floor(you.getZ()) + 0.5 + dz;
		bot.setPos(x, you.getY(), z);
		// Facing you (north, when you face south).
		bot.setYRot(180);
		bot.setYHeadRot(180);
		bot.yBodyRot = 180;
		bot.yBodyRotO = 180;
		bot.yHeadRotO = 180;
		bot.setOldPosAndRot();
		level.addPlayer(entityId, bot);
		BOTS.put(id, entityId);
		return name + " " + id + " " + mode + (mode.equals("unlisted") ? " (listed: 1.19.2 lists every player it knows)" : "") + " at " + x + " "
			+ you.getY() + " " + z;
	}

	/**
	 * The armour stand the tour summoned next to you rides you, as a server hangs its own name above a player: on the client,
	 * as its passengers packet does. Minecraft 1.19.2 has no text displays (1.19.4 brought them), so servers hung a named,
	 * invisible marker armour stand there, and the tour summons one ({@code command summon armor_stand ...}). Minecraft's own
	 * server refuses players as vehicles. {@code unmount} takes it off again.
	 */
	public static String mount() {
		return riding(true);
	}

	public static String unmount() {
		return riding(false);
	}

	private static String riding(boolean on) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer you = mc.player;
		if (you == null || mc.level == null) {
			return "no world";
		}
		List<String> done = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof ArmorStand && entity.distanceToSqr(you) < 64) {
				if (on) {
					done.add("rides you: " + QaRide.ride(entity, you));
				} else {
					entity.stopRiding();
					done.add("off you");
				}
			}
		}
		return "armour stand " + done + "; you carry passengers: " + you.isVehicle();
	}

	/** Minecraft 1.19.2 has no unlisted entries: a player it knows stays in the Tab list. */
	public static String unlist(UUID id) {
		return "not unlisted " + id + " (1.19.2 lists every player it knows)";
	}

	public static String clear() {
		Minecraft mc = Minecraft.getInstance();
		List<String> gone = new ArrayList<>();
		if (mc.level != null && mc.getConnection() != null) {
			ClientPacketListenerAccessor connection = (ClientPacketListenerAccessor) mc.getConnection();
			for (Map.Entry<UUID, Integer> bot : BOTS.entrySet()) {
				mc.level.removeEntity(bot.getValue(), Entity.RemovalReason.DISCARDED);
				connection.flintqa$playerInfoMap().remove(bot.getKey());
				gone.add(bot.getKey().toString());
			}
		}
		BOTS.clear();
		return "removed " + gone;
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
