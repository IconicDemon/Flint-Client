package com.flintclient.client.platform;

import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.Waypoint;
import com.flintclient.client.control.model.mods.UtilityMods;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.living.player.PlayerEntity;
import org.jspecify.annotations.Nullable;

/**
 * Where the player is, for the waypoint list, on Minecraft 1.8.9: the dimension (by the ids newer Minecraft names them with,
 * so waypoints read the same on every line), the position, the block stood on, and a waypoint added there. Platform class
 * (see {@link Clock}). Render thread.
 */
public final class World {
	private static final int[] PALETTE = {0xFF2F20, 0xFF9F1C, 0xFFE530, 0x21D85B, 0x19B7FF, 0x5D38FF, 0xF045B7};

	private World() {
	}

	/** 1.8.9's dimension number as a dimension id. */
	public static String dimensionId(int dimension) {
		return switch (dimension) {
			case -1 -> "minecraft:the_nether";
			case 1 -> "minecraft:the_end";
			default -> "minecraft:overworld";
		};
	}

	/** A dimension id as 1.8.9's number. */
	public static int dimensionNumber(String id) {
		return switch (id) {
			case "minecraft:the_nether" -> -1;
			case "minecraft:the_end" -> 1;
			default -> 0;
		};
	}

	/** The dimension's id ({@code minecraft:overworld}), or null outside a world. */
	public static @Nullable String dimension() {
		Minecraft mc = Minecraft.getInstance();
		return mc.world == null || mc.player == null ? null : dimensionId(mc.player.dimension);
	}

	/** The player's position, or null outside a world. */
	public static double @Nullable [] position() {
		PlayerEntity player = Minecraft.getInstance().player;
		return player == null ? null : new double[] {player.x, player.y, player.z};
	}

	/** The block the player stands on, or null outside a world. */
	public static int @Nullable [] blockPosition() {
		PlayerEntity player = Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}
		double y = player.y;
		int floor = (int) Math.floor(y);
		return new int[] {(int) Math.floor(player.x), !player.onGround ? floor - 1 : y - floor >= 0.5 ? floor : floor - 1, (int) Math.floor(player.z)};
	}

	/** Adds a waypoint called {@code name} in {@code rgb} where the player stands; its index, or -1 outside a world. */
	public static int addWaypointHere(String name, int rgb) {
		String dimension = dimension();
		int[] at = blockPosition();
		if (dimension == null || at == null) {
			return -1;
		}
		return Features.waypoints().add(new Waypoint(name, at[0], at[1], at[2], dimension, rgb, true)
			.withGroup(Features.waypoints().activeSet(dimension)));
	}

	/** The colour the next waypoint gets (Waypoints' random colours, or its one colour). */
	public static int nextWaypointColor() {
		if (!Mods.state(Mods.index(UtilityMods.Waypoints.ID)).bool(UtilityMods.Waypoints.RANDOM_COLORS)) {
			return 0xFF8139;
		}
		int made = 0;
		for (Waypoint w : Features.waypoints().all()) {
			if (!w.death()) {
				made++;
			}
		}
		return PALETTE[made % PALETTE.length];
	}
}
