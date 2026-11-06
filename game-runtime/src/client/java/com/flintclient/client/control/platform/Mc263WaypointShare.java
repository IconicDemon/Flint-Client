package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.Waypoint;
import com.flintclient.client.control.feature.WaypointShare;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * Waypoints in chat: a line holding a shared waypoint or coordinates gets an "[+ Waypoint]" after it; clicking it runs
 * Flint's own command (answered here, never sent), which adds the waypoint to the active set of its dimension and says so.
 * Fault-isolated; render thread.
 */
public final class Mc263WaypointShare {
	private static final int WAYPOINTS = Mods.index(UtilityMods.Waypoints.ID);
	private static final HookGuard GUARD = new HookGuard("shared waypoints",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} path failed; it's off for this session", path, error));

	private Mc263WaypointShare() {
	}

	/** A chat line on its way in: itself, or itself with a click to add the waypoint it holds. */
	public static Component incoming(Component line) {
		if (!GUARD.on() || !Mods.on(WAYPOINTS)) {
			return line;
		}
		try {
			ModuleState s = Mods.state(WAYPOINTS);
			if (!s.bool(UtilityMods.Waypoints.CHAT_LINKS)) {
				return line;
			}
			String text = line.getString();
			if (text.contains(WaypointShare.COMMAND)) {
				return line;
			}
			WaypointShare.Found found = WaypointShare.find(text, s.bool(UtilityMods.Waypoints.CHAT_NUMBERS));
			String dimension = Mc263Features.dimension();
			if (found == null || dimension == null) {
				return line;
			}
			String name = found.name() != null ? found.name() : "Shared " + found.x() + " " + found.z();
			String command = "/" + WaypointShare.command(found, dimension, name);
			String where = found.x() + ", " + found.y() + ", " + found.z()
				+ (found.dimension() != null ? " in the " + WaypointShare.dimensionName(found.dimension()) : "");
			MutableComponent add = Component.literal(" [+ Waypoint]").withStyle(style -> style.withColor(0xFF8139).withBold(true)
				.withClickEvent(new ClickEvent.RunCommand(command)).withHoverEvent(new HoverEvent.ShowText(Component.literal("Add “" + name
					+ "” at " + where))));
			return line.copy().append(add);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return line;
		}
	}

	/** A command on its way to the server: true when it was a shared waypoint's click, answered here instead. */
	public static boolean command(String command) {
		WaypointShare.Found found = WaypointShare.parseCommand(command);
		if (found == null) {
			return false;
		}
		String dimension = found.dimension() != null ? found.dimension() : Mc263Features.dimension();
		if (dimension != null) {
			add(dimension, found.x(), found.y(), found.z(), found.name() != null ? found.name() : Features.waypoints().nextName("Shared"));
		}
		return true;
	}

	/** Adds a shared waypoint to the active set of its dimension and says so in chat; returns its index or -1. */
	public static int add(String dimension, int x, int y, int z, String name) {
		int index = Features.waypoints().add(new Waypoint(name, x, y, z, dimension, 0x4FC3F7, true)
			.withGroup(Features.waypoints().activeSet(dimension)));
		Component message = index >= 0
			? Component.literal("Added waypoint “" + name + "” at " + x + ", " + y + ", " + z).withStyle(ChatFormatting.GRAY)
			: Component.literal("Couldn't add the waypoint (join a world, or too many waypoints)").withStyle(ChatFormatting.RED);
		com.flintclient.client.compat.McCompat.hud().getChat().addClientSystemMessage(message);
		return index;
	}
}
