package com.flintclient.client.module.social;

import com.flintclient.client.module.account.LauncherLink;
import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Friends and the Flint Store for the game, through the game link to the Flint app that started it ({@link LauncherLink},
 * the launcher's {@code social/game.rs} and {@code store/game.rs}): the Friends page's state and actions, the account's Store
 * items, wearing one, and an item's picture. The sessions stay the launcher's: the game sends only what it plays as
 * ({@code playing}) and what the player did.
 *
 * <p>Answers are untrusted: every field is checked here (UUIDs are 32 hex digits, statuses one of the known ones, text cut
 * short and without control characters, lists capped) and anything malformed is left out. Calls block: run them off the
 * render thread. Platform-free (Gson and sockets).
 */
public final class SocialLink {
	/** A list or a poll: Flint answers from memory. */
	private static final int QUICK_MILLIS = 6000;
	/** An action may sign in first (the launcher allows 45 s). */
	private static final int ACTION_MILLIS = 50_000;
	/** The Store: an inventory, or an equip and the inventory after it (the launcher allows 40 s). */
	private static final int STORE_MILLIS = 45_000;
	/** 300 friends, 200 requests and a chat's 50 messages of up to 1,000 characters fit with room to spare. */
	private static final int MAX_FRIENDS_REPLY = 512 * 1024;
	/** An item's picture: a PNG of up to 1 MiB as base64. */
	private static final int MAX_ART_REPLY = 1_500_000;
	static final int MAX_ART_BYTES = 1024 * 1024;
	static final int MAX_FRIENDS = 300;
	static final int MAX_REQUESTS = 100;
	static final int MAX_MESSAGES = 200;
	static final int MAX_PLAYERS = 50;
	static final int MAX_ITEMS = 500;
	public static final int MAX_BODY = 1000;
	static final int MAX_NAME = 32;

	/** The statuses a friend shows, and the ones the player can pick. */
	public static final List<String> FRIEND_STATUSES = List.of("online", "busy", "away", "offline");
	public static final List<String> MY_STATUSES = List.of("online", "busy", "away", "invisible");
	public static final List<String> SLOTS = List.of("cape", "back", "head", "aura");

	private final LauncherLink link;

	public SocialLink(LauncherLink link) {
		this.link = link;
	}

	/** This game's link, or null when Flint didn't start it. */
	public static @Nullable SocialLink get() {
		LauncherLink link = LauncherLink.get();
		return link == null ? null : new SocialLink(link);
	}

	// ---------- what comes back ----------

	public record Me(String uuid, String name, String status, boolean allowRequests) {
	}

	public record Friend(String uuid, String name, String status, boolean inGame, long lastSeen, boolean pinned, int unread) {
		public boolean online() {
			return !"offline".equals(status);
		}
	}

	/** A friend request; {@code id} goes back to Flint exactly as it came (a number or a short string). */
	public record Request(JsonPrimitive id, String uuid, String name, long at) {
		public String key() {
			return id.getAsString();
		}
	}

	public record Message(long id, String from, String to, String body, boolean sensitive, long at, boolean read) {
	}

	/** The chat the game has open: the latest messages (oldest first); {@code more}: Flint has older ones. */
	public record Chat(String with, List<Message> messages, boolean loaded, boolean more) {
	}

	/**
	 * The Friends page's state. {@code phase}: signed_out, connecting, unavailable, ready or error; {@code error} a short
	 * code then (or when a ready state couldn't be refreshed); {@code offline}: the last sync found no network.
	 */
	public record Friends(boolean configured, String phase, boolean testIdentity, @Nullable Me me, List<Friend> friends, List<Request> incoming,
		List<Request> outgoing, boolean offline, boolean stale, @Nullable String error, @Nullable Chat chat) {
		public boolean ready() {
			return "ready".equals(phase);
		}

		/** What the Friends button counts: requests waiting and unread messages. */
		public int badge() {
			if (!ready() && !stale) {
				return 0;
			}
			int count = incoming.size();
			for (Friend friend : friends) {
				count += friend.unread();
			}
			return count;
		}

		public @Nullable Friend friend(String uuid) {
			for (Friend friend : friends) {
				if (friend.uuid().equals(uuid)) {
					return friend;
				}
			}
			return null;
		}
	}

	/** A player found by name. {@code relation}: none, friend, incoming, outgoing or self. */
	public record Player(String uuid, String name, String relation, boolean accepts) {
	}

	/** A page of older messages; {@code complete}: there are none before it. */
	public record Page(List<Message> messages, boolean complete) {
	}

	/** One Store item the account owns; {@code art}: its picture's version, or -1 when it has none. */
	public record Item(String id, String name, String kind, boolean gift, @Nullable String giftFrom, boolean test, long art) {
	}

	public record Cosmetics(String storeUrl, List<Item> items, Map<String, String> equipped, boolean stale, @Nullable String problem) {
		public @Nullable String worn(String slot) {
			return equipped.get(slot);
		}
	}

	// ---------- Friends ----------

	/** The Friends page's state for the account the game plays as ({@code playing}), with the chat it has open. */
	public Friends friends(String playing, @Nullable String chat) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("playing", playing);
		if (chat != null) {
			fields.addProperty("chat", chat);
		}
		return readFriends(link.request("friends", fields, QUICK_MILLIS, MAX_FRIENDS_REPLY), chat);
	}

	/** Runs one of the page's actions ({@code {"kind": "send", …}}) and returns Flint's answer. */
	public JsonObject act(String playing, JsonObject action) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("playing", playing);
		fields.add("action", action);
		return link.request("friendsAction", fields, ACTION_MILLIS, MAX_FRIENDS_REPLY);
	}

	public static JsonObject action(String kind) {
		JsonObject action = new JsonObject();
		action.addProperty("kind", kind);
		return action;
	}

	/** Sends a friend request: "sent", or "accepted" when they had already asked. */
	public String request(String playing, String name) throws LinkException {
		JsonObject action = action("request");
		action.addProperty("name", name);
		String result = string(act(playing, action), "result");
		return "accepted".equals(result) ? "accepted" : "sent";
	}

	public void respond(String playing, Request request, boolean accept) throws LinkException {
		JsonObject action = action("respond");
		action.add("id", request.id());
		action.addProperty("accept", accept);
		act(playing, action);
	}

	public void cancel(String playing, Request request) throws LinkException {
		JsonObject action = action("cancel");
		action.add("id", request.id());
		act(playing, action);
	}

	public void remove(String playing, String friend) throws LinkException {
		JsonObject action = action("remove");
		action.addProperty("uuid", friend);
		act(playing, action);
	}

	public void pin(String playing, String friend, boolean pinned) throws LinkException {
		JsonObject action = action("pin");
		action.addProperty("uuid", friend);
		action.addProperty("pinned", pinned);
		act(playing, action);
	}

	/** Sends a message; {@code clientId} (a UUID, the same on every retry) keeps a retry from storing it twice. */
	public Message send(String playing, String to, String body, boolean sensitive, String clientId) throws LinkException {
		JsonObject action = action("send");
		action.addProperty("to", to);
		action.addProperty("body", body);
		action.addProperty("sensitive", sensitive);
		action.addProperty("clientId", clientId);
		JsonElement message = act(playing, action).get("message");
		Message sent = message != null && message.isJsonObject() ? readMessage(message.getAsJsonObject()) : null;
		if (sent == null) {
			throw new LinkException("invalid", false);
		}
		return sent;
	}

	/** Older messages with {@code friend}, before message {@code before} (0: the latest page). */
	public Page history(String playing, String friend, long before) throws LinkException {
		JsonObject action = action("history");
		action.addProperty("with", friend);
		if (before > 0) {
			action.addProperty("before", before);
		}
		JsonObject reply = act(playing, action);
		List<Message> messages = new ArrayList<>();
		for (JsonObject entry : objects(reply, "messages", MAX_MESSAGES)) {
			Message message = readMessage(entry);
			if (message != null && (message.from().equals(friend) || message.to().equals(friend))) {
				messages.add(message);
			}
		}
		messages.sort((a, b) -> Long.compare(a.id(), b.id()));
		return new Page(List.copyOf(messages), bool(reply, "complete"));
	}

	public void read(String playing, String friend, long upTo) throws LinkException {
		JsonObject action = action("read");
		action.addProperty("with", friend);
		action.addProperty("upTo", upTo);
		act(playing, action);
	}

	/** Changes the player's status ({@link #MY_STATUSES}) and/or whether they take requests; null leaves one as it is. */
	public @Nullable Me settings(String playing, @Nullable String status, @Nullable Boolean allowRequests) throws LinkException {
		JsonObject action = action("settings");
		if (status != null) {
			action.addProperty("status", status);
		}
		if (allowRequests != null) {
			action.addProperty("allowRequests", allowRequests);
		}
		JsonElement me = act(playing, action).get("me");
		return me != null && me.isJsonObject() ? readMe(me.getAsJsonObject()) : null;
	}

	public List<Player> search(String playing, String query) throws LinkException {
		JsonObject action = action("search");
		action.addProperty("query", query);
		List<Player> players = new ArrayList<>();
		for (JsonObject entry : objects(act(playing, action), "players", MAX_PLAYERS)) {
			String uuid = uuid(entry, "uuid");
			String name = name(entry, "name");
			if (uuid != null && name != null) {
				players.add(new Player(uuid, name, oneOf(entry, "relation", List.of("none", "friend", "incoming", "outgoing", "self"), "none"),
					bool(entry, "accepts")));
			}
		}
		return List.copyOf(players);
	}

	/** Retry: Flint signs in now if needed, then syncs. */
	public void connect(String playing) throws LinkException {
		act(playing, action("connect"));
	}

	// ---------- the Flint Store ----------

	/** The account's Store items and what it wears; an error (to show as it is) when it isn't signed in to the Store. */
	public Cosmetics cosmetics(String playing) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("playing", playing);
		return readCosmetics(link.request("cosmetics", fields, STORE_MILLIS, MAX_FRIENDS_REPLY));
	}

	/** Wears {@code id} in {@code slot} (null: empties it); the items after it. */
	public Cosmetics equip(String playing, String slot, @Nullable String id) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("playing", playing);
		fields.addProperty("slot", slot);
		if (id != null) {
			fields.addProperty("id", id);
		}
		return readCosmetics(link.request("equip", fields, STORE_MILLIS, MAX_FRIENDS_REPLY));
	}

	/** The PNG of an item the account owns, as the Store hashed it. */
	public byte[] art(String playing, String id) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("playing", playing);
		fields.addProperty("id", id);
		return readArt(link.request("cosmeticArt", fields, STORE_MILLIS, MAX_ART_REPLY));
	}

	// ---------- reading answers ----------

	static Friends readFriends(JsonObject reply, @Nullable String chatWith) {
		String phase = oneOf(reply, "phase", List.of("signed_out", "connecting", "unavailable", "ready", "error"), "error");
		JsonElement meJson = reply.get("me");
		Me me = meJson != null && meJson.isJsonObject() ? readMe(meJson.getAsJsonObject()) : null;
		List<Friend> friends = new ArrayList<>();
		for (JsonObject entry : objects(reply, "friends", MAX_FRIENDS)) {
			String uuid = uuid(entry, "uuid");
			String name = name(entry, "name");
			if (uuid != null && name != null) {
				friends.add(new Friend(uuid, name, oneOf(entry, "status", FRIEND_STATUSES, "offline"), bool(entry, "inGame"),
					Math.max(0, number(entry, "lastSeen")), bool(entry, "pinned"), (int) Math.min(99_999, Math.max(0, number(entry, "unread")))));
			}
		}
		Chat chat = null;
		JsonElement chatJson = reply.get("chat");
		if (chatWith != null && chatJson != null && chatJson.isJsonObject()) {
			JsonObject c = chatJson.getAsJsonObject();
			if (chatWith.equals(uuid(c, "with"))) {
				List<Message> messages = new ArrayList<>();
				for (JsonObject entry : objects(c, "messages", MAX_MESSAGES)) {
					Message message = readMessage(entry);
					if (message != null && (message.from().equals(chatWith) || message.to().equals(chatWith))) {
						messages.add(message);
					}
				}
				messages.sort((a, b) -> Long.compare(a.id(), b.id()));
				chat = new Chat(chatWith, List.copyOf(messages), bool(c, "loaded"), bool(c, "more"));
			}
		}
		String error = string(reply, "error");
		return new Friends(!reply.has("configured") || bool(reply, "configured"), phase, bool(reply, "testIdentity"), me, List.copyOf(friends),
			readRequests(reply, "incoming"), readRequests(reply, "outgoing"), bool(reply, "offline"), bool(reply, "stale"),
			error == null ? null : code(error), chat);
	}

	static @Nullable Me readMe(JsonObject me) {
		String uuid = uuid(me, "uuid");
		String name = name(me, "name");
		if (uuid == null || name == null) {
			return null;
		}
		JsonElement allow = me.get("allowRequests");
		return new Me(uuid, name, oneOf(me, "status", MY_STATUSES, "online"), allow == null || !allow.isJsonPrimitive() || bool(me, "allowRequests"));
	}

	private static List<Request> readRequests(JsonObject reply, String key) {
		List<Request> requests = new ArrayList<>();
		for (JsonObject entry : objects(reply, key, MAX_REQUESTS)) {
			JsonPrimitive id = requestId(entry.get("id"));
			String uuid = uuid(entry, "uuid");
			String name = name(entry, "name");
			if (id != null && uuid != null && name != null) {
				requests.add(new Request(id, uuid, name, Math.max(0, number(entry, "at"))));
			}
		}
		return List.copyOf(requests);
	}

	/** A request id as Flint sends it back: a whole number, or 1–64 letters, digits and dashes. */
	static @Nullable JsonPrimitive requestId(@Nullable JsonElement id) {
		if (id == null || !id.isJsonPrimitive()) {
			return null;
		}
		JsonPrimitive p = id.getAsJsonPrimitive();
		if (p.isNumber()) {
			try {
				return new JsonPrimitive(p.getAsBigDecimal().longValueExact());
			} catch (ArithmeticException | NumberFormatException e) {
				return null;
			}
		}
		if (p.isString() && p.getAsString().matches("[A-Za-z0-9-]{1,64}")) {
			return p;
		}
		return null;
	}

	static @Nullable Message readMessage(JsonObject entry) {
		JsonElement idJson = entry.get("id");
		long id;
		try {
			id = idJson != null && idJson.isJsonPrimitive() ? idJson.getAsJsonPrimitive().getAsBigDecimal().longValueExact() : -1;
		} catch (ArithmeticException | NumberFormatException e) {
			id = -1;
		}
		String from = uuid(entry, "from");
		String to = uuid(entry, "to");
		String body = string(entry, "body");
		if (id < 0 || from == null || to == null || body == null) {
			return null;
		}
		return new Message(id, from, to, clean(body, MAX_BODY, true), bool(entry, "sensitive"), Math.max(0, number(entry, "at")), bool(entry, "read"));
	}

	static Cosmetics readCosmetics(JsonObject reply) {
		List<Item> items = new ArrayList<>();
		for (JsonObject entry : objects(reply, "items", MAX_ITEMS)) {
			String id = cosmeticId(string(entry, "id"));
			String kind = oneOf(entry, "kind", SLOTS, "");
			String name = string(entry, "name");
			if (id != null && !kind.isEmpty() && name != null) {
				JsonElement art = entry.get("art");
				long version = art != null && art.isJsonPrimitive() && art.getAsJsonPrimitive().isNumber() ? Math.max(0, number(entry, "art")) : -1;
				String from = string(entry, "giftFrom");
				items.add(new Item(id, clean(name, 120, false), kind, bool(entry, "gift"), from == null ? null : clean(from, MAX_NAME, false),
					bool(entry, "test"), version));
			}
		}
		Map<String, String> equipped = new LinkedHashMap<>();
		JsonElement worn = reply.get("equipped");
		if (worn != null && worn.isJsonObject()) {
			for (String slot : SLOTS) {
				String id = cosmeticId(string(worn.getAsJsonObject(), slot));
				if (id != null) {
					equipped.put(slot, id);
				}
			}
		}
		String problem = string(reply, "problem");
		String store = string(reply, "storeUrl");
		return new Cosmetics(store != null && store.matches("https?://[A-Za-z0-9.:\\[\\]-]{1,200}") ? store : "https://store.flintclient.com",
			List.copyOf(items), Collections.unmodifiableMap(equipped), bool(reply, "stale"), problem == null ? null : clean(problem, 300, false));
	}

	static byte[] readArt(JsonObject reply) throws LinkException {
		String png = string(reply, "png");
		if (png == null || png.length() > MAX_ART_BYTES * 4 / 3 + 8) {
			throw new LinkException("Flint sent a picture this game can't read.", false);
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(png);
		} catch (IllegalArgumentException e) {
			throw new LinkException("Flint sent a picture this game can't read.", false);
		}
		if (bytes.length < 8 || bytes.length > MAX_ART_BYTES || (bytes[0] & 0xFF) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
			throw new LinkException("Flint sent a picture this game can't read.", false);
		}
		return bytes;
	}

	// ---------- small checks ----------

	/** A Minecraft UUID in Flint's form (32 lowercase hex), from either spelling; null for anything else. */
	public static @Nullable String canonical(@Nullable String uuid) {
		if (uuid == null || uuid.length() > 36) {
			return null;
		}
		String hex = uuid.replace("-", "").toLowerCase(Locale.ROOT);
		return hex.matches("[0-9a-f]{32}") ? hex : null;
	}

	/** A Store cosmetic id: a UUID with its dashes, lowercase. */
	static @Nullable String cosmeticId(@Nullable String id) {
		return id != null && id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ? id.toLowerCase(Locale.ROOT) : null;
	}

	/** An error as Flint codes it ({@code not_on_flint}, {@code offline}…): letters, digits and underscores, short. */
	public static String code(String error) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < error.length() && out.length() < 40; i++) {
			char c = error.charAt(i);
			if (c < 128 && (Character.isLetterOrDigit(c) || c == '_')) {
				out.append(c);
			}
		}
		return out.length() == 0 ? "invalid" : out.toString();
	}

	/** {@code text} without control characters (newlines kept in a message), at most {@code max} characters. */
	static String clean(String text, int max, boolean newlines) {
		StringBuilder out = new StringBuilder(Math.min(text.length(), max));
		for (int i = 0; i < text.length() && out.length() < max; i++) {
			char c = text.charAt(i);
			if (c == '§') {
				// A Minecraft formatting code (§ and the letter after it, as Minecraft strips them): a friend's text never
				// colours, hides or scrambles what the game draws.
				i++;
			} else if (c == '\n' && newlines) {
				out.append(c);
			} else if (c == '\t') {
				out.append(' ');
			} else if (c >= ' ' && c != 0x7F && !(c >= 0x80 && c < 0xA0) && c != 0x202E && c != 0x202D) {
				out.append(c);
			}
		}
		if (out.length() > 0 && Character.isHighSurrogate(out.charAt(out.length() - 1))) {
			out.setLength(out.length() - 1);
		}
		return out.toString();
	}

	private static @Nullable String name(JsonObject object, String key) {
		String value = string(object, key);
		if (value == null) {
			return null;
		}
		String name = clean(value, MAX_NAME, false).strip();
		return name.isEmpty() ? null : name;
	}

	private static @Nullable String uuid(JsonObject object, String key) {
		return canonical(string(object, key));
	}

	private static String oneOf(JsonObject object, String key, List<String> allowed, String otherwise) {
		String value = string(object, key);
		return value != null && allowed.contains(value) ? value : otherwise;
	}

	private static List<JsonObject> objects(JsonObject object, String key, int max) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonArray()) {
			return List.of();
		}
		JsonArray array = value.getAsJsonArray();
		List<JsonObject> out = new ArrayList<>(Math.min(array.size(), max));
		for (int i = 0; i < array.size() && out.size() < max; i++) {
			if (array.get(i).isJsonObject()) {
				out.add(array.get(i).getAsJsonObject());
			}
		}
		return out;
	}

	static @Nullable String string(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	private static boolean bool(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
	}

	private static long number(JsonObject object, String key) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			return 0;
		}
		try {
			double d = value.getAsDouble();
			return Double.isFinite(d) ? (long) Math.max(Long.MIN_VALUE / 2, Math.min(Long.MAX_VALUE / 2, d)) : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
