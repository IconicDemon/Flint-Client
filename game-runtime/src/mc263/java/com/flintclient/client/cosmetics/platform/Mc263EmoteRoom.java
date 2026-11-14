package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.rshift.FlintFriendsScreen;
import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.ModelCosmetics;
import com.flintclient.client.cosmetics.ModelItem;
import com.flintclient.client.module.social.SocialLink;
import com.flintclient.client.platform.Texture;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerRedirectHandler;
import org.jspecify.annotations.Nullable;

/**
 * Emotes seen by others on 26.3: while you play on a server (never singleplayer or LAN), the Flint app keeps that server's
 * emote room open (launcher {@code social/emote_room.rs}). The game tells it which server ({@code emoteRoom}, the address
 * after Minecraft's own SRV lookup, as Minecraft connects), your emotes as they start and stop ({@link Mc263Emotes#RELAY}),
 * and asks for the others' ({@code emoteEvents}, waited on, on a thread of its own). Another player's emote plays on them
 * ({@link Mc263Emotes#remote}) when they are in the world you see, started as long ago as the room says; their stop ends it.
 * One of yours the room refuses (not owned, or 2 s since the last) shows a line in the log. Only your Store emotes go to the
 * room (dev emotes stay on you).
 */
public final class Mc263EmoteRoom {
	private static final int WAIT_MS = 20_000;
	private static @Nullable String joined;
	private static volatile @Nullable String room;
	private static volatile boolean asking;
	private static final ConcurrentLinkedQueue<JsonObject> IN = new ConcurrentLinkedQueue<>();
	/** Your emotes and stops go out one at a time, in the order you made them (a stop then a new emote must not swap). */
	private static final ExecutorService OUT = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Flint emote relay");
		t.setDaemon(true);
		return t;
	});

	private Mc263EmoteRoom() {
	}

	/** The server you play on, as Minecraft names it (null: singleplayer, LAN, a Realm, or none). */
	static @Nullable String server(Minecraft mc) {
		ServerData data = mc.getCurrentServer();
		if (data == null || mc.isLocalServer() || data.isLan() || data.isRealm() || mc.getConnection() == null) {
			return null;
		}
		return data.ip;
	}

	/** Every tick: follows the server you join and leave, and plays what the room said. */
	static void tick(Minecraft mc, ModelCosmetics<Texture> s) {
		String now = server(mc);
		if (!java.util.Objects.equals(now, joined)) {
			joined = now;
			announce(now);
		}
		JsonObject event;
		while ((event = IN.poll()) != null) {
			play(mc, s, event);
		}
	}

	/** Tells the app the room to be in (off the game's thread: the SRV lookup and the link may take a moment). */
	private static void announce(@Nullable String typed) {
		Thread t = new Thread(() -> {
			SocialLink link = SocialLink.get();
			String who = FlintFriendsScreen.playing();
			if (link == null || who == null) {
				return;
			}
			String address = typed == null ? null : resolved(typed);
			try {
				link.emoteRoom(who, address);
				room = address;
				if (address != null) {
					listen(link, who);
				}
			} catch (Exception | LinkageError e) {
				FlintClient.LOGGER.warn("Flint emotes: the Flint app didn't open the server's emote room: {}", e.toString());
			}
		}, "Flint emote room");
		t.setDaemon(true);
		t.start();
	}

	/** {@code host:port} as Minecraft connects: its SRV record's target when it has one. */
	static String resolved(String typed) {
		ServerAddress address = ServerAddress.parseString(typed);
		try {
			Optional<ServerAddress> redirect = ServerRedirectHandler.createDnsSrvRedirectHandler().lookupRedirect(address);
			if (redirect.isPresent()) {
				address = redirect.get();
			}
		} catch (RuntimeException e) {
			// No SRV record (or no DNS): the address as typed.
		}
		String host = address.getHost();
		return (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + address.getPort();
	}

	/** Waits on the room's events while the game is in a room (one thread at a time, following the room as it changes). */
	private static void listen(SocialLink link, String who) {
		if (asking) {
			return;
		}
		asking = true;
		try {
			long since = 0;
			boolean first = true;
			String at = null;
			while (room != null) {
				String current = room;
				if (current == null) {
					break;
				}
				if (!current.equals(at)) {
					// Another server: its room's own events, from its start.
					at = current;
					since = 0;
					first = true;
				}
				JsonObject reply;
				try {
					reply = link.emoteEvents(who, since, first ? 0 : WAIT_MS);
				} catch (Exception e) {
					Thread.sleep(5_000);
					continue;
				}
				JsonElement seq = reply.get("seq");
				long next = seq != null && seq.isJsonPrimitive() ? seq.getAsLong() : since;
				// The first answer is the room's past: only what comes after it is played.
				if (!first && reply.get("events") != null && reply.get("events").isJsonArray()) {
					JsonObject items = reply.get("items") != null && reply.get("items").isJsonObject() ? reply.getAsJsonObject("items") : new JsonObject();
					for (JsonElement e : reply.getAsJsonArray("events")) {
						if (e.isJsonObject()) {
							JsonObject event = e.getAsJsonObject().deepCopy();
							String id = event.get("cosmeticId") != null && event.get("cosmeticId").isJsonPrimitive() ? event.get("cosmeticId").getAsString() : null;
							if (id != null && items.get(id) != null) {
								event.add("item", items.get(id));
							}
							if (IN.size() < 64) {
								IN.add(event);
							}
						}
					}
				}
				first = false;
				since = next;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			asking = false;
		}
	}

	/** One event of the room's, on the game's thread. */
	private static void play(Minecraft mc, ModelCosmetics<Texture> s, JsonObject event) {
		String kind = string(event, "kind");
		if ("refused".equals(kind)) {
			FlintClient.LOGGER.info("Flint emotes: the room didn't show your emote to others ({})", string(event, "reason"));
			return;
		}
		UUID player = uuid(string(event, "uuid"));
		if (player == null || mc.level == null || mc.level.getPlayerByUUID(player) == null) {
			// Only for players in the world you see.
			return;
		}
		if ("stop".equals(kind)) {
			Mc263Emotes.remoteStop(player);
			return;
		}
		JsonObject item = event.get("item") != null && event.get("item").isJsonObject() ? event.getAsJsonObject("item") : null;
		ModelItem emote = item == null ? null : ModelItem.read(item, CosmeticKind.EMOTE, s.endpoint());
		long ago = event.get("agoMs") != null && event.get("agoMs").isJsonPrimitive() ? event.get("agoMs").getAsLong() : 0;
		if (emote != null) {
			Mc263Emotes.remote(player, emote, ago);
		}
	}

	/** Your emote, to the room (a Store emote only), off the game's thread. */
	static void relay(String message) {
		String who = FlintFriendsScreen.playing();
		SocialLink link = SocialLink.get();
		if (room == null || who == null || link == null) {
			return;
		}
		String id = message.startsWith("start ") ? message.substring(6) : null;
		if (id != null && !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
			return;
		}
		OUT.execute(() -> {
			try {
				link.emoteSend(who, id != null ? "emote" : "stop", id);
			} catch (Exception | LinkageError e) {
				// Others just don't see this one.
			}
		});
	}

	private static @Nullable String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static @Nullable UUID uuid(@Nullable String text) {
		if (text == null) {
			return null;
		}
		String t = text.toLowerCase(Locale.ROOT).replace("-", "");
		if (!t.matches("[0-9a-f]{32}")) {
			return null;
		}
		return UUID.fromString(t.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
	}
}
