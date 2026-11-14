package com.flintclient.client.mc;

/** A server's address as typed, as newer Minecraft names it (see {@link Component}): 1.8.9 resolves it when it connects. */
public record ServerAddress(String text) {
	public static ServerAddress parseString(String text) {
		return new ServerAddress(text);
	}
}
