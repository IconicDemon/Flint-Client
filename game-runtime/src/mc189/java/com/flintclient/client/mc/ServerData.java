package com.flintclient.client.mc;

import java.util.Base64;
import org.jspecify.annotations.Nullable;

/** A saved server as newer Minecraft names it, over Minecraft 1.8.9's server list entry (see {@link Component}). */
public final class ServerData {
	public enum Type {
		LAN,
		REALM,
		OTHER
	}

	public final String name;
	public final String ip;
	private final net.minecraft.client.options.ServerListEntry entry;

	public ServerData(String name, String ip, Type type) {
		this(new net.minecraft.client.options.ServerListEntry(name, ip, type == Type.LAN));
	}

	ServerData(net.minecraft.client.options.ServerListEntry entry) {
		this.entry = entry;
		this.name = entry.name;
		this.ip = entry.ip;
	}

	/** 1.8.9's own entry (for connecting). */
	public net.minecraft.client.options.ServerListEntry entry() {
		return entry;
	}

	/** Its saved icon (a PNG), or null. */
	public byte @Nullable [] getIconBytes() {
		String icon = entry.getIcon();
		if (icon == null || icon.isEmpty()) {
			return null;
		}
		try {
			return Base64.getDecoder().decode(icon.startsWith("data:image/png;base64,") ? icon.substring(22) : icon);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
