package com.flintclient.qa;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Cape-derived elytra tours (the runtime's control/platform/Mc263CapeElytra), without a second game. Dev-only.
 * <ul>
 * <li>{@code call com.flintclient.qa.QaElytra.option on|off}: Flint's "Cape design on elytra" setting, as the menu sets it.</li>
 * <li>{@code call com.flintclient.qa.QaElytra.bot uuid:UUID off|folded|glide YAW}: a {@link QaBots} player's chest slot (an
 * elytra or nothing), gliding or not (the entity's fall-flying flag, as a server's entity data sets it), facing YAW degrees
 * (0: south, away from you when you face south; 180: towards you).</li>
 * </ul>
 */
public final class QaElytra {
	private QaElytra() {
	}

	public static String option(String on) throws ReflectiveOperationException {
		Object settings = Class.forName("com.flintclient.client.control.ControlState").getMethod("settings").invoke(null);
		Object option = Class.forName("com.flintclient.client.control.model.GameOptions").getField("CAPE_ELYTRA").get(null);
		Method set = settings.getClass().getMethod("setGame", Class.forName("com.flintclient.client.control.model.SettingDescriptor"), Object.class);
		Object changed = set.invoke(settings, option, on.equals("on"));
		return "cape design on elytra " + on + " (changed: " + changed + ")";
	}

	public static String bot(UUID id, String mode, int yaw) throws ReflectiveOperationException {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return "no world";
		}
		RemotePlayer bot = null;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof RemotePlayer p && p.getUUID().equals(id)) {
				bot = p;
			}
		}
		if (bot == null) {
			return "no bot " + id;
		}
		// Every skin part shown (cape included), as a server's entity data says for a player with Minecraft's defaults: a player
		// made on the client alone has none, so Minecraft (and Flint, which follows it) would draw no cape at all.
		for (Class<?> c = bot.getClass(); c != null; c = c.getSuperclass()) {
			try {
				java.lang.reflect.Field field = QaNames.field(c, "DATA_PLAYER_MODE_CUSTOMISATION");
				field.setAccessible(true);
				@SuppressWarnings("unchecked")
				net.minecraft.network.syncher.EntityDataAccessor<Byte> parts = (net.minecraft.network.syncher.EntityDataAccessor<Byte>) field.get(null);
				bot.getEntityData().set(parts, (byte) 0x7F);
				break;
			} catch (NoSuchFieldException e) {
				// declared on a superclass
			}
		}
		bot.setItemSlot(EquipmentSlot.CHEST, mode.equals("off") ? ItemStack.EMPTY : new ItemStack(Items.ELYTRA));
		Method flag = QaNames.method(Entity.class, "setSharedFlag", int.class, boolean.class);
		flag.setAccessible(true);
		// Entity's FLAG_FALL_FLYING.
		flag.invoke(bot, 7, mode.equals("glide"));
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.yBodyRot = yaw;
		bot.yBodyRotO = yaw;
		bot.yHeadRotO = yaw;
		return "bot " + id + " " + mode + " facing " + yaw + " (fall flying: " + bot.isFallFlying() + ")";
	}
}
