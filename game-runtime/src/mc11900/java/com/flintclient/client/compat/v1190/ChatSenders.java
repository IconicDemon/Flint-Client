package com.flintclient.client.compat.v1190;

import com.flintclient.client.control.platform.Mc263ChatHeads;
import com.mojang.authlib.GameProfile;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ChatSender;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Who sent the chat message Minecraft 1.19 (1.19.0) is showing now. 1.19.0 has no ChatListener (1.19.1 brought it, with its
 * delay queue and message tags): the HUD's handlePlayerChat and handleSystemChat hand each message to the chat window as it
 * arrives (mixin.feature.ChatHeadsListenerMixin tells this class around them), and the chat window itself holds player messages
 * back while the Chat Delay option asks it to. So what the message is (a player's, one with the system as its sender: a
 * server's /say, the server's own) is told to Chat Heads as it is shown, a held-back message keeps it until the chat shows it
 * (mixin.feature.ChatComponentMixin), and a kept message remembers whether a player sent it, as 1.19.1's message tags tell
 * ({@link #fromPlayer}). Render thread only.
 */
public final class ChatSenders {
	private static final int NONE = 0;
	private static final int PLAYER = 1;
	private static final int DISGUISED = 2;
	private static final int SYSTEM = 3;

	/** What a message being shown is, and its sender's profile for a player's. */
	private record Sender(int kind, @Nullable GameProfile profile) {
	}

	private static final Sender NOBODY = new Sender(NONE, null);
	private static Sender current = NOBODY;
	/** Player messages the chat holds back (Chat Delay), by identity, with who sent them; a few at most. */
	private static final Map<Component, Sender> QUEUED = new IdentityHashMap<>();
	/** The kept messages a player sent, by identity (weakly: the chat drops old ones). */
	private static final Set<GuiMessage<?>> FROM_PLAYERS = java.util.Collections.newSetFromMap(
		new com.google.common.collect.MapMaker().weakKeys().<GuiMessage<?>, Boolean>makeMap());

	private ChatSenders() {
	}

	/** A player's chat message is being shown ({@code sender} is the server's word; the nil UUID means the system sent it). */
	public static void player(ChatSender sender) {
		if (Util.NIL_UUID.equals(sender.uuid())) {
			show(new Sender(DISGUISED, null));
			return;
		}
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		PlayerInfo info = connection == null ? null : connection.getPlayerInfo(sender.uuid());
		show(new Sender(PLAYER, info != null ? info.getProfile() : new GameProfile(sender.uuid(), sender.name().getString())));
	}

	/** A system message from the server is being shown ({@code inChat}: it goes into chat, not only the action bar). */
	public static void system(boolean inChat) {
		current = new Sender(SYSTEM, null);
		Mc263ChatHeads.systemMessage(inChat);
	}

	/** The message has been handed over. */
	public static void done() {
		current = NOBODY;
		Mc263ChatHeads.messageDone();
	}

	private static void show(Sender sender) {
		current = sender;
		if (sender.kind == PLAYER) {
			Mc263ChatHeads.playerMessage(sender.profile);
		} else {
			Mc263ChatHeads.disguisedMessage();
		}
	}

	/** The chat holds {@code message} back (Chat Delay): who sent it goes with it. */
	public static void queued(Component message) {
		if (current.kind == NONE) {
			return;
		}
		if (QUEUED.size() > 200) {
			QUEUED.clear();
		}
		QUEUED.put(message, current);
	}

	/** The chat shows a held-back {@code message} now: true when its sender was known (then {@link #done} after). */
	public static boolean dequeued(Component message) {
		Sender sender = QUEUED.remove(message);
		if (sender == null) {
			return false;
		}
		show(sender);
		return true;
	}

	/** The chat keeps {@code message} (shown now): whether a player sent it is remembered. */
	public static void kept(GuiMessage<?> message) {
		if (current.kind == PLAYER) {
			FROM_PLAYERS.add(message);
		}
	}

	/** Whether a player sent the kept {@code message} (1.19.1 tells by its tag: the client's and server's own are system). */
	public static boolean fromPlayer(GuiMessage<?> message) {
		return FROM_PLAYERS.contains(message);
	}
}
