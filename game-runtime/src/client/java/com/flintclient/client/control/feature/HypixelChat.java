package com.flintclient.client.control.feature;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What the Hypixel mod trusts in chat. Its automations send commands, so they only run on Hypixel itself and only for
 * Hypixel's own lines: a friend request is a line that starts with "Friend request from", which a player's chat line
 * (it starts with their name) can't, and requests are accepted at most once every {@link #ACCEPT_GAP_MS}, so text a
 * player or a server repeats can't make you send command after command. No Minecraft classes.
 */
public final class HypixelChat {
	/** The least time between two automatic friend accepts. */
	public static final long ACCEPT_GAP_MS = 2_000;

	private static final Pattern HOST = Pattern.compile("[a-z0-9.-]{1,253}");
	private static final Pattern FRIEND_REQUEST = Pattern.compile("^\\s*Friend request from (?:\\[[^\\]]+\\] )?(\\w{1,16})\\b", Pattern.MULTILINE);

	private long lastAccept = Long.MIN_VALUE;

	/** Whether a server address ({@code mc.hypixel.net}, {@code hypixel.net:25565}) is Hypixel's own. */
	public static boolean isHypixel(@Nullable String address) {
		if (address == null) {
			return false;
		}
		String host = address.strip().toLowerCase(Locale.ROOT);
		int port = host.lastIndexOf(':');
		if (port >= 0 && host.indexOf(':') == port) {
			host = host.substring(0, port);
		}
		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		return HOST.matcher(host).matches() && (host.equals("hypixel.net") || host.endsWith(".hypixel.net"));
	}

	/** Who sent the friend request in Hypixel's message {@code text} (formatting already stripped), else null. */
	public static @Nullable String friendRequestSender(String text) {
		Matcher m = FRIEND_REQUEST.matcher(text);
		return m.find() ? m.group(1) : null;
	}

	/** Whether to accept a request at {@code now} (milliseconds); true marks it accepted. */
	public boolean mayAccept(long now) {
		if (lastAccept != Long.MIN_VALUE && now - lastAccept < ACCEPT_GAP_MS) {
			return false;
		}
		lastAccept = now;
		return true;
	}
}
