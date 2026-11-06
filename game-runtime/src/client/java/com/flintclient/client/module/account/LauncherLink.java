package com.flintclient.client.module.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The game's end of Flint's game link (the launcher's {@code gamelink.rs}): a loopback channel to the Flint app that started
 * this game, so the main menu's account card can list Flint's accounts, switch to one and add one. Accounts and their
 * tokens stay Flint's: the game only ever holds the session of the account it plays as, as Minecraft always does.
 *
 * <p>Flint hands the link to the games it starts in their environment ({@value #ENV_ADDRESS}: {@code 127.0.0.1:port};
 * {@value #ENV_SECRET}: this launch's secret). A game started some other way, or after Flint quit, has no link (or a dead
 * one) and the card says so. One connection per request, one JSON line each way. Calls block: run them off the render
 * thread. Nothing here logs a request or an answer (a session's answer carries its token).
 *
 * <p>Music Player asks the same link what's playing on this computer ({@link #media}), for its album art ({@link #mediaArt})
 * and to press its buttons ({@link #mediaControl}): Flint reads Windows' own media controls for it.
 *
 * <p>Platform-free (Gson and sockets): every Minecraft line compiles it, 1.8.9 included.
 */
public final class LauncherLink {
	public static final String ENV_ADDRESS = "FLINT_LAUNCHER_LINK";
	public static final String ENV_SECRET = "FLINT_LAUNCHER_LINK_SECRET";
	static final int PROTOCOL = 1;
	private static final int CONNECT_MILLIS = 1500;
	private static final int QUICK_MILLIS = 6000;
	/** A session may wait on a Microsoft renewal and Minecraft's sign-in. */
	private static final int SESSION_MILLIS = 95_000;
	/** Flint gives up on Windows' media controls after 4 seconds itself. */
	private static final int MEDIA_MILLIS = 5000;
	private static final int MAX_REPLY = 64 * 1024;
	/** Album art: at most 256 × 256 RGBA pixels in base64 (350 KB), with room for the rest of the line. */
	private static final int MAX_ART_REPLY = 512 * 1024;
	/** The largest album art accepted, px square. */
	static final int MAX_ART_SIZE = 256;
	/** Titles and names are cut to this many characters (a HUD line shows far fewer). */
	private static final int MAX_TEXT = 200;

	private static volatile @Nullable LauncherLink current;
	private static volatile boolean read;

	private final InetSocketAddress address;
	private final String secret;

	LauncherLink(InetSocketAddress address, String secret) {
		this.address = address;
		this.secret = secret;
	}

	/** This game's link, or null when Flint didn't start it (or handed it something that isn't a loopback address). */
	public static @Nullable LauncherLink get() {
		if (!read) {
			current = fromEnvironment(System.getenv());
			read = true;
		}
		return current;
	}

	static @Nullable LauncherLink fromEnvironment(Map<String, String> env) {
		String address = env.get(ENV_ADDRESS);
		String secret = env.get(ENV_SECRET);
		if (address == null || secret == null || !secret.matches("[0-9a-f]{64}")) {
			return null;
		}
		int colon = address.lastIndexOf(':');
		if (colon <= 0 || !"127.0.0.1".equals(address.substring(0, colon))) {
			return null;
		}
		try {
			int port = Integer.parseInt(address.substring(colon + 1));
			if (port <= 0 || port > 65535) {
				return null;
			}
			return new LauncherLink(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), secret);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ---------- answers ----------

	/** One of Flint's accounts: no token. {@code offline}: a test identity (QA builds of Flint only). */
	public record Account(String uuid, String username, boolean offline) {
	}

	/** Where a sign-in this game asked Flint for stands: idle, running, done (with the new account) or failed. */
	public record SignIn(String state, @Nullable String uuid, @Nullable String username, @Nullable String message, @Nullable String url) {
		static final SignIn IDLE = new SignIn("idle", null, null, null, null);

		public boolean running() {
			return "running".equals(state);
		}

		public boolean done() {
			return "done".equals(state);
		}

		public boolean failed() {
			return "failed".equals(state);
		}
	}

	public record Accounts(@Nullable String active, List<Account> list, SignIn signIn) {
	}

	/** A Minecraft session from Flint, for {@link SessionSwitch}. {@link #toString} leaves the token out. */
	public record Session(String uuid, String username, String accessToken, boolean offline) {
		@Override
		public String toString() {
			return "Session[" + username + ", " + uuid + (offline ? ", offline]" : "]");
		}
	}

	/**
	 * What's playing on this computer, as Windows' media controls show it. {@code supported} false: Flint can't tell here
	 * ({@code reason} says why). {@code active}: there is a track ({@code title} is set). Times in milliseconds; a length of 0
	 * means the app doesn't say. {@code artVersion} changes with the album art (0: none).
	 */
	public record Media(boolean supported, @Nullable String reason, boolean active, boolean playing, String title, String artist, String album,
		String app, long positionMs, long durationMs, int artVersion, boolean canPrevious, boolean canNext, boolean canToggle, boolean canSeek) {
	}

	/** Album art: {@code width × height} pixels, row by row, four bytes each (red, green, blue, alpha). */
	public record Art(int version, int width, int height, byte[] rgba) {
	}

	/** Flint answered no (its message is for the player), or couldn't be reached. */
	public static final class LinkException extends IOException {
		private final boolean unreachable;

		public LinkException(String message, boolean unreachable) {
			super(message);
			this.unreachable = unreachable;
		}

		/** True when Flint isn't running (any more) or didn't answer: the card then says to open the app. */
		public boolean unreachable() {
			return unreachable;
		}
	}

	// ---------- requests ----------

	public Accounts accounts() throws LinkException {
		JsonObject reply = call(op("accounts"), QUICK_MILLIS);
		List<Account> list = new ArrayList<>();
		JsonArray accounts = reply.has("accounts") && reply.get("accounts").isJsonArray() ? reply.getAsJsonArray("accounts") : new JsonArray();
		for (JsonElement element : accounts) {
			if (element.isJsonObject()) {
				JsonObject account = element.getAsJsonObject();
				String uuid = string(account, "uuid");
				String name = string(account, "username");
				if (uuid != null && name != null) {
					list.add(new Account(uuid, name, account.has("offline") && account.get("offline").getAsBoolean()));
				}
			}
		}
		SignIn signIn = SignIn.IDLE;
		if (reply.has("signIn") && reply.get("signIn").isJsonObject()) {
			JsonObject s = reply.getAsJsonObject("signIn");
			signIn = new SignIn(String.valueOf(string(s, "state")), string(s, "uuid"), string(s, "username"), string(s, "message"), string(s, "url"));
		}
		return new Accounts(string(reply, "active"), List.copyOf(list), signIn);
	}

	public Session session(String uuid) throws LinkException {
		JsonObject request = op("session");
		request.addProperty("uuid", uuid);
		JsonObject reply = call(request, SESSION_MILLIS);
		String id = string(reply, "uuid");
		String name = string(reply, "username");
		String token = string(reply, "accessToken");
		if (id == null || name == null || token == null || !id.equals(uuid)) {
			throw new LinkException("Flint sent a session Minecraft can't use.", false);
		}
		return new Session(id, name, token, reply.has("offline") && reply.get("offline").getAsBoolean());
	}

	public void activate(String uuid) throws LinkException {
		JsonObject request = op("activate");
		request.addProperty("uuid", uuid);
		call(request, QUICK_MILLIS);
	}

	/**
	 * Starts Flint's Microsoft sign-in and returns its page, which this game opens (it is in front: a page Flint opened from
	 * the background would stay behind the game). Flint still waits for Microsoft's redirect and makes the account. When a
	 * sign-in from this game is already waiting, its page again. Null only from a Flint that opened the page itself.
	 */
	public @Nullable String addAccount() throws LinkException {
		JsonObject request = op("addAccount");
		request.addProperty("opens", true);
		JsonObject reply = call(request, QUICK_MILLIS);
		return string(reply, "url");
	}

	/** Signs {@code uuid} out of Flint (as the app's account menu does); Flint refuses {@code playing}, the game's own account. */
	public void removeAccount(String uuid, String playing) throws LinkException {
		JsonObject request = op("removeAccount");
		request.addProperty("uuid", uuid);
		request.addProperty("playing", playing);
		call(request, QUICK_MILLIS);
	}

	public void cancelAdd() throws LinkException {
		call(op("cancelAdd"), QUICK_MILLIS);
	}

	// ---------- Music Player ----------

	/** What's playing now; {@code musicOnly} leaves browsers and video apps out. A Flint from before Music Player says "bad request". */
	public Media media(boolean musicOnly) throws LinkException {
		JsonObject request = op("media");
		if (musicOnly) {
			request.addProperty("musicOnly", true);
		}
		return readMedia(call(request, MEDIA_MILLIS, MAX_REPLY));
	}

	static Media readMedia(JsonObject reply) {
		return new Media(bool(reply, "supported"), string(reply, "reason"), bool(reply, "active"), bool(reply, "playing"), text(reply, "title"),
			text(reply, "artist"), text(reply, "album"), text(reply, "app"), Math.max(0, number(reply, "positionMs")),
			Math.max(0, number(reply, "durationMs")), (int) Math.max(0, Math.min(Integer.MAX_VALUE, number(reply, "artVersion"))),
			bool(reply, "canPrevious"), bool(reply, "canNext"), bool(reply, "canToggle"), bool(reply, "canSeek"));
	}

	/** The album art of what's playing, {@code size} px square (Flint keeps it at most 256). */
	public Art mediaArt(int size) throws LinkException {
		JsonObject request = op("mediaArt");
		request.addProperty("size", Math.max(1, Math.min(MAX_ART_SIZE, size)));
		return readArt(call(request, MEDIA_MILLIS, MAX_ART_REPLY));
	}

	static Art readArt(JsonObject reply) throws LinkException {
		long width = number(reply, "width");
		long height = number(reply, "height");
		String rgba = string(reply, "rgba");
		if (width < 1 || height < 1 || width > MAX_ART_SIZE || height > MAX_ART_SIZE || rgba == null) {
			throw new LinkException("Flint sent a picture this game can't read.", false);
		}
		byte[] pixels;
		try {
			pixels = Base64.getDecoder().decode(rgba);
		} catch (IllegalArgumentException e) {
			throw new LinkException("Flint sent a picture this game can't read.", false);
		}
		if (pixels.length != width * height * 4) {
			throw new LinkException("Flint sent a picture this game can't read.", false);
		}
		return new Art((int) Math.max(0, Math.min(Integer.MAX_VALUE, number(reply, "version"))), (int) width, (int) height, pixels);
	}

	/**
	 * Presses a button of what's playing: {@code toggle}, {@code next}, {@code previous}, or {@code seek} to {@code positionMs}.
	 * {@code musicOnly} picks the same session {@link #media} shows.
	 */
	public void mediaControl(String action, long positionMs, boolean musicOnly) throws LinkException {
		JsonObject request = op("mediaControl");
		request.addProperty("action", action);
		if ("seek".equals(action)) {
			request.addProperty("positionMs", Math.max(0, positionMs));
		}
		if (musicOnly) {
			request.addProperty("musicOnly", true);
		}
		call(request, MEDIA_MILLIS, MAX_REPLY);
	}

	// ---------- Friends and the Flint Store (module/social/SocialLink) ----------

	/**
	 * One request of the Friends and Store operations ({@code friends}, {@code friendsAction}, {@code cosmetics}, {@code equip},
	 * {@code cosmeticArt}): {@code fields} added to the request, the answer (its {@code ok} checked) within
	 * {@code timeoutMillis} and at most {@code maxReply} characters. What the answer holds is the caller's to check.
	 */
	public JsonObject request(String name, JsonObject fields, int timeoutMillis, int maxReply) throws LinkException {
		JsonObject request = op(name);
		for (Map.Entry<String, JsonElement> field : fields.entrySet()) {
			if (!request.has(field.getKey())) {
				request.add(field.getKey(), field.getValue());
			}
		}
		return call(request, timeoutMillis, maxReply);
	}

	private JsonObject op(String name) {
		JsonObject request = new JsonObject();
		request.addProperty("v", PROTOCOL);
		request.addProperty("secret", secret);
		request.addProperty("op", name);
		return request;
	}

	private JsonObject call(JsonObject request, int timeoutMillis) throws LinkException {
		return call(request, timeoutMillis, MAX_REPLY);
	}

	private JsonObject call(JsonObject request, int timeoutMillis, int maxReply) throws LinkException {
		String line;
		try (Socket socket = new Socket()) {
			socket.connect(address, CONNECT_MILLIS);
			socket.setSoTimeout(timeoutMillis);
			OutputStream out = socket.getOutputStream();
			out.write((request + "\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			line = readLine(in, maxReply);
		} catch (IOException e) {
			throw new LinkException("Flint isn't reachable.", true);
		}
		JsonObject reply;
		try {
			reply = JsonParser.parseString(line).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new LinkException("Flint sent an answer this game can't read.", false);
		}
		if (!bool(reply, "ok")) {
			String error = string(reply, "error");
			if ("unauthorized".equals(error)) {
				// The Flint listening there didn't start this game (it was restarted since): as good as no Flint.
				throw new LinkException("Flint isn't reachable.", true);
			}
			throw new LinkException(error == null ? "Flint couldn't do that." : error, false);
		}
		return reply;
	}

	private static String readLine(BufferedReader in, int maxReply) throws IOException {
		StringBuilder line = new StringBuilder();
		for (int c; (c = in.read()) != -1 && c != '\n';) {
			if (line.length() >= maxReply) {
				throw new IOException("reply too long");
			}
			line.append((char) c);
		}
		return line.toString();
	}

	private static @Nullable String string(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	/** Text to show on the HUD: "" when missing, no control characters, at most {@link #MAX_TEXT} characters. */
	private static String text(JsonObject object, String key) {
		String value = string(object, key);
		if (value == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_TEXT));
		for (int i = 0; i < value.length() && out.length() < MAX_TEXT; i++) {
			char c = value.charAt(i);
			out.append(c < ' ' || c == 0x7F ? ' ' : c);
		}
		// A cut mustn't leave half of a character made of two.
		if (out.length() > 0 && Character.isHighSurrogate(out.charAt(out.length() - 1))) {
			out.setLength(out.length() - 1);
		}
		return out.toString().strip();
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
			return value.getAsLong();
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
