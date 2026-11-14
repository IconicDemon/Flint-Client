package com.flintclient.client.mc;

import org.jspecify.annotations.Nullable;

/** The player's saved servers (servers.dat) as newer Minecraft names them, over Minecraft 1.8.9's list (see {@link Component}). */
public final class ServerList {
	private final net.minecraft.client.options.ServerList list;

	public ServerList(Minecraft minecraft) {
		this.list = new net.minecraft.client.options.ServerList(Minecraft.game());
	}

	public void load() {
		list.load();
	}

	public int size() {
		return list.size();
	}

	public ServerData get(int index) {
		return new ServerData(list.get(index));
	}

	/** The saved server at {@code address}, or null. */
	public @Nullable ServerData get(String address) {
		for (int i = 0; i < list.size(); i++) {
			net.minecraft.client.options.ServerListEntry entry = list.get(i);
			if (entry.ip.equals(address)) {
				return new ServerData(entry);
			}
		}
		return null;
	}
}
