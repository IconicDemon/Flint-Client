package com.flintclient.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;

/**
 * Sets the QA player's hunger, saturation and exhaustion in a single-player world, for tours of the hunger bar:
 * {@code call com.flintclient.qa.QaFood.set 14 35 25} (food points, saturation and exhaustion in tenths). Runs on the
 * server thread; the client hears of it with the next health update. Dev-only, like the rest of this mod.
 */
public final class QaFood {
	private QaFood() {
	}

	public static String set(int food, int saturationTenths, int exhaustionTenths) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return "no single-player server";
		}
		server.execute(() -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				FoodData data = player.getFoodData();
				data.setFoodLevel(food);
				data.setSaturation(saturationTenths / 10F);
				try {
					QaNames.field(FoodData.class, "exhaustionLevel").setFloat(data, exhaustionTenths / 10F);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException(e);
				}
			}
		});
		return "food " + food + ", saturation " + saturationTenths / 10F + ", exhaustion " + exhaustionTenths / 10F;
	}
}
