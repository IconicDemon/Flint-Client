package com.flintclient.qa;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 3D cosmetics tours (the runtime's cosmetics/ and cosmetics/platform/), with {@link QaBots} players as the wearers. Dev-only.
 * <ul>
 * <li>{@code call com.flintclient.qa.QaCosmetics.hold uuid:UUID shield|none}: the bot's main hand.</li>
 * <li>{@code call com.flintclient.qa.QaCosmetics.pose uuid:UUID stand|crouch|swim YAW}: the bot's pose, facing YAW degrees.</li>
 * <li>{@code call com.flintclient.qa.QaCosmetics.walk uuid:UUID SPEED}: the bot's legs swing as when walking (0 stops).</li>
 * <li>{@code call com.flintclient.qa.QaCosmetics.storeCapes on|off}: Flint's "Flint Store capes" setting (Store capes and the 3D
 * cosmetics), as the menu sets it.</li>
 * </ul>
 */
public final class QaCosmetics {
	private QaCosmetics() {
	}

	private static RemotePlayer bot(UUID id) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof RemotePlayer p && p.getUUID().equals(id)) {
				return p;
			}
		}
		return null;
	}

	public static String hold(UUID id, String item) {
		RemotePlayer bot = bot(id);
		if (bot == null) {
			return "no bot " + id;
		}
		bot.setItemInHand(InteractionHand.MAIN_HAND, item.equals("shield") ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
		return "bot " + id + " holds " + item;
	}

	public static String pose(UUID id, String pose, int yaw) {
		RemotePlayer bot = bot(id);
		if (bot == null) {
			return "no bot " + id;
		}
		bot.setPose(switch (pose) {
			case "crouch" -> Pose.CROUCHING;
			case "swim" -> Pose.SWIMMING;
			default -> Pose.STANDING;
		});
		bot.setShiftKeyDown(pose.equals("crouch"));
		bot.setSwimming(pose.equals("swim"));
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.yBodyRot = yaw;
		bot.yBodyRotO = yaw;
		bot.yHeadRotO = yaw;
		return "bot " + id + " " + pose + " facing " + yaw;
	}

	public static String walk(UUID id, int speedPercent) {
		RemotePlayer bot = bot(id);
		if (bot == null) {
			return "no bot " + id;
		}
		bot.walkAnimation.setSpeed(speedPercent / 100F);
		return "bot " + id + " walking at " + speedPercent + "%";
	}

	public static String storeCapes(String on) throws ReflectiveOperationException {
		Object settings = Class.forName("com.flintclient.client.control.ControlState").getMethod("settings").invoke(null);
		Object option = Class.forName("com.flintclient.client.control.model.GameOptions").getField("STORE_CAPES").get(null);
		java.lang.reflect.Method set = settings.getClass().getMethod("setGame", Class.forName("com.flintclient.client.control.model.SettingDescriptor"),
			Object.class);
		return "Flint Store capes " + on + " (changed: " + set.invoke(settings, option, on.equals("on")) + ")";
	}
}
