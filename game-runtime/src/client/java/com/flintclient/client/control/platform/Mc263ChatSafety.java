package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.chat.ChatTrustLevel;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.entity.player.ProfileKeyPair;
import org.jspecify.annotations.Nullable;

/**
 * No Chat Reports' client: which servers your chat is safe on, whether your client may sign, and the per-server signing
 * modes. The hooks ({@code mixin.ncr}) ask here; the chat screen's safety button, the multiplayer menu's buttons and the
 * warning screens ({@code ui.screen.ChatSafetyScreens}) show it.
 *
 * <p>The core is a key manager that keeps its keys to itself: without a key your client has no chat session, so what you
 * send carries no signature. Where a server insists on signed chat, the signing mode decides: Never ignores it, Always signs
 * from the start, Prompt asks with a warning screen, On Demand signs as soon as the server asks (and sends the refused message
 * again). Signing lasts for the session.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt): {@code ServerSafetyState},
 * {@code ServerSafetyLevel}, {@code SigningMode}, {@code NCRServerPreferences}, {@code NCRClient} and its client mixins.
 */
public final class Mc263ChatSafety {
	private static final int MODULE = Mods.index(NoChatReports.ID);
	static final HookGuard GUARD = new HookGuard("no chat reports",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** How safe chatting on the current server is. */
	public enum Level {
		SECURE,
		SINGLEPLAYER,
		UNINTRUSIVE,
		INSECURE,
		REALMS,
		UNKNOWN,
		UNDEFINED;

		public MutableComponent tooltip() {
			return Component.translatable("flintclient.ncr.gui.safety_status." + name().toLowerCase(java.util.Locale.ROOT));
		}

		public boolean secure() {
			return this == SECURE || this == SINGLEPLAYER;
		}

		/** The safety button's picture for this level. */
		public String sprite() {
			return switch (this) {
				case INSECURE -> "insecure";
				case UNINTRUSIVE -> "unintrusive";
				case SECURE, SINGLEPLAYER -> "secure";
				case REALMS -> "realms";
				case UNKNOWN -> "unknown";
				case UNDEFINED -> "undefined";
			};
		}
	}

	/** When your client signs on a server. */
	public enum Mode {
		DEFAULT,
		NEVER,
		ALWAYS,
		PROMPT,
		ON_DEMAND,
		NEVER_FORCED;

		/** The setting's choices, in its order. */
		static final Mode[] SETTING = {NEVER, ALWAYS, PROMPT, ON_DEMAND};

		String key() {
			return "flintclient.ncr.gui.signing_mode." + name().toLowerCase(java.util.Locale.ROOT);
		}

		public MutableComponent title() {
			return this == DEFAULT ? Component.translatable(key(), defaultMode().title()) : Component.translatable(key());
		}

		public MutableComponent tooltip() {
			return Component.translatable(key() + ".tooltip");
		}

		public Mode resolve() {
			return switch (this) {
				case DEFAULT -> defaultMode();
				case NEVER_FORCED -> NEVER;
				default -> this;
			};
		}
	}

	/** What the ServerData mixin adds to a server list entry: whether its status says it prevents chat reports. */
	public interface ServerFlag {
		boolean flint$preventsReports();

		void flint$setPreventsReports(boolean prevents);
	}

	private static final AtomicBoolean ALLOW_SIGNING = new AtomicBoolean();
	private static final List<Runnable> SIGNING_ACTIONS = new ArrayList<>();
	private static volatile Level current = Level.UNDEFINED;
	private static volatile @Nullable ServerAddress lastServer;
	private static volatile String lastMessage = "???";
	private static boolean hideThisSession;
	private static volatile boolean keysPresent;
	private static boolean keysChecked;
	/** Whether the last server status JSON parsed on this thread said it prevents chat reports. */
	private static final ThreadLocal<Boolean> STATUS_PREVENTS = new ThreadLocal<>();
	private static final Map<Class<?>, Optional<Field>> SERVER_DATA_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
	private static @Nullable Map<String, Mode> serverModes;

	private Mc263ChatSafety() {
	}

	// ---------- the switches ----------

	/** The module is on. */
	public static boolean on() {
		try {
			return GUARD.on() && Mods.on(MODULE);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** On, with its "Enable mod" (most of the client side). */
	public static boolean enabled() {
		return on() && bool(NoChatReports.ENABLE_MOD);
	}

	public static boolean bool(BoolSetting setting) {
		return Mods.state(MODULE).bool(setting);
	}

	/** On and {@code setting} on. */
	public static boolean with(BoolSetting setting) {
		return on() && bool(setting);
	}

	public static int integer(com.flintclient.client.control.model.IntSetting setting) {
		return Mods.state(MODULE).integer(setting);
	}

	static Mode defaultMode() {
		return Mode.SETTING[Math.max(0, Math.min(3, Mods.state(MODULE).option(NoChatReports.DEFAULT_MODE)))];
	}

	static void debug(String message, Object... args) {
		if (with(NoChatReports.DEBUG_LOG)) {
			FlintClient.LOGGER.info("[No Chat Reports] " + message, args);
		}
	}

	// ---------- the state ----------

	public static Level current() {
		return current;
	}

	static void update(Level level) {
		current = level;
	}

	public static boolean allowSigning() {
		return ALLOW_SIGNING.get();
	}

	public static boolean onRealms() {
		return current == Level.REALMS;
	}

	public static boolean inSingleplayer() {
		return current == Level.SINGLEPLAYER;
	}

	static boolean determined() {
		return current != Level.UNINTRUSIVE && current != Level.UNDEFINED && current != Level.UNKNOWN;
	}

	public static @Nullable ServerAddress lastServer() {
		return lastServer;
	}

	public static boolean keysPresent() {
		return keysPresent;
	}

	public static boolean hideThisSession() {
		return hideThisSession;
	}

	public static void setHideThisSession(boolean hide) {
		hideThisSession = hide;
	}

	/** Lets your client sign (or not) for this session; signing starts with a fresh key and session. */
	public static CompletableFuture<Void> setAllowSigning(boolean allow) {
		if (ALLOW_SIGNING.compareAndSet(!allow, allow)) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && allow) {
				ClientPacketListener connection = mc.player.connection;
				Session session = (Session) connection;
				if (session.flint$chatSession() == null) {
					return mc.getProfileKeyPairManager().prepareKeyPair().thenAcceptAsync(key -> key.ifPresent(pair -> {
						session.flint$setKeyPair(pair);
						List<Runnable> actions = new ArrayList<>(SIGNING_ACTIONS);
						SIGNING_ACTIONS.clear();
						actions.forEach(Runnable::run);
					}), mc);
				}
			}
		}
		return CompletableFuture.completedFuture(null);
	}

	/** What the chat session accessor on ClientPacketListener offers. */
	public interface Session {
		@Nullable Object flint$chatSession();

		void flint$setKeyPair(ProfileKeyPair pair);

		boolean flint$signableArguments(String command);
	}

	public static void reset() {
		lastServer = null;
		current = Level.UNDEFINED;
		ALLOW_SIGNING.set(false);
		hideThisSession = false;
		SIGNING_ACTIONS.clear();
	}

	/** Sends the message the server refused unsigned again, now signed. */
	static void resendLastMessage() {
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener connection = mc.getConnection();
		String message = lastMessage;
		if (connection == null || message.isEmpty()) {
			return;
		}
		if (message.startsWith("/")) {
			connection.sendCommand(message.substring(1));
		} else {
			connection.sendChat(message);
		}
	}

	// ---------- signing modes ----------

	/** The server's mode as chosen (DEFAULT when it has none), or what the situation forces. */
	public static Mode modeUnresolved(@Nullable ServerAddress address) {
		if (!keysPresent) {
			return Mode.NEVER_FORCED;
		} else if (inSingleplayer()) {
			return Mode.NEVER;
		} else if (onRealms()) {
			return Mode.ALWAYS;
		} else if (address == null) {
			return Mode.DEFAULT;
		}
		Mode mode = serverModes().get(key(address));
		return mode != null ? mode : Mode.DEFAULT;
	}

	public static Mode mode(@Nullable ServerAddress address) {
		return modeUnresolved(address).resolve();
	}

	static boolean currentModeIs(Mode mode) {
		return mode(lastServer) == mode;
	}

	public static void setMode(@Nullable ServerAddress address, @Nullable Mode mode) {
		if (address == null) {
			return;
		}
		Map<String, Mode> modes = serverModes();
		if (mode != null && mode != Mode.DEFAULT) {
			modes.put(key(address), mode);
		} else {
			modes.remove(key(address));
		}
		saveModes();
	}

	private static String key(ServerAddress address) {
		return address.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + address.getPort();
	}

	private static Path modesFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("flintclient").resolve("no-chat-reports-servers.json");
	}

	private static Map<String, Mode> serverModes() {
		if (serverModes == null) {
			reloadModes();
		}
		return serverModes;
	}

	/** Reads the servers' signing modes from their file again (the multiplayer menu's reload button). */
	public static void reloadModes() {
		Map<String, Mode> modes = new LinkedHashMap<>();
		Path file = modesFile();
		if (Files.isRegularFile(file)) {
			try {
				JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
				if (root.isJsonObject() && root.getAsJsonObject().get("signingModes") instanceof JsonObject servers) {
					for (Map.Entry<String, JsonElement> e : servers.entrySet()) {
						try {
							Mode mode = Mode.valueOf(e.getValue().getAsString());
							if (mode != Mode.DEFAULT && mode != Mode.NEVER_FORCED) {
								modes.put(e.getKey(), mode);
							}
						} catch (RuntimeException | LinkageError ignored) {
							// Not a mode: left out.
						}
					}
				}
			} catch (IOException | RuntimeException e) {
				FlintClient.LOGGER.warn("Flint: couldn't read {}", file, e);
			}
		}
		serverModes = modes;
		debug("Signing modes loaded: {}", modes.size());
	}

	private static void saveModes() {
		JsonObject root = new JsonObject();
		root.addProperty("schema", 1);
		JsonObject servers = new JsonObject();
		serverModes().forEach((server, mode) -> servers.addProperty(server, mode.name()));
		root.add("signingModes", servers);
		Path file = modesFile();
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			FlintClient.LOGGER.warn("Flint: couldn't write {}", file, e);
		}
	}

	// ---------- each client tick ----------

	/** Once: whether this account has chat signing keys at all (offline accounts never do). Asked off the render thread. */
	public static void tick() {
		if (keysChecked || !GUARD.on()) {
			return;
		}
		keysChecked = true;
		try {
			ProfileKeyPairManager real = ((Keys) Minecraft.getInstance()).flint$realKeys();
			real.prepareKeyPair().whenComplete((key, error) -> keysPresent = error == null && key.isPresent());
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** The Minecraft accessor: its own key manager, past the hook that hides it. */
	public interface Keys {
		ProfileKeyPairManager flint$realKeys();
	}

	/** Whether Minecraft's key manager should look empty: on, Enable mod, signing not allowed. */
	public static boolean hideKeys() {
		return enabled() && !ALLOW_SIGNING.get();
	}

	// ---------- connections ----------

	public static void connecting(ServerAddress address, @Nullable ServerData data) {
		if (!enabled()) {
			return;
		}
		reset();
		lastServer = address;
		debug("Connecting to: {}", address.getHost() + ":" + address.getPort());
		debug("Server Data IP: {}", data != null ? data.ip : "null");
	}

	public static void connectingToRealms() {
		if (!enabled()) {
			return;
		}
		reset();
		current = Level.REALMS;
		setAllowSigning(true);
	}

	/** Joined a world (ClientPacketListener.handleLogin, main thread): how safe it is. */
	public static void joined(ClientPacketListener listener) {
		if (!enabled()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			try {
				if (!mc.isLocalServer()) {
					if (!onRealms()) {
						if (!McCompat.onlineMode(listener)) {
							current = Level.SECURE;
						} else if (mc.getCurrentServer() instanceof ServerFlag flag && flag.flint$preventsReports()) {
							current = Level.SECURE;
						} else if (mode(lastServer) == Mode.ALWAYS) {
							current = Level.INSECURE;
							setAllowSigning(true);
						} else {
							current = Level.UNKNOWN;
						}
					}
				} else {
					current = Level.SINGLEPLAYER;
				}
				debug("Successfully connected to server, safety state: {}", current);
				if (bool(NoChatReports.DEMAND_ON_SERVER) && !current.secure()) {
					listener.getConnection().disconnect(Component.translatable("flintclient.ncr.disconnect.client"));
				}
			} catch (RuntimeException | LinkageError e) {
				GUARD.fail(e);
			}
		});
	}

	public static void left() {
		if (!enabled()) {
			return;
		}
		debug("Disconnected from server, resetting safety state!");
		reset();
	}

	/** A packet your client sends: unsigned chat or a signable command the server takes unsigned means it doesn't insist. */
	public static void sending(Object listener, Packet<?> packet) {
		if (!on() || !(listener instanceof ClientPacketListener play) || allowSigning() || determined()) {
			return;
		}
		if (packet instanceof net.minecraft.network.protocol.game.ServerboundChatPacket) {
			current = Level.UNINTRUSIVE;
		} else if (packet instanceof net.minecraft.network.protocol.game.ServerboundChatCommandPacket command
			&& ((Session) play).flint$signableArguments(command.command())) {
			current = Level.UNINTRUSIVE;
		}
	}

	// ---------- chat ----------

	private static boolean redispatching;

	/**
	 * A system message: the server's "this needs a signature" notice becomes No Chat Reports' explanation, and the signing
	 * mode decides what happens (On Demand signs and sends it again, Prompt shows the warning screen). True when it's handled
	 * here (the original not shown).
	 */
	public static boolean systemMessage(Component message, boolean overlay, java.util.function.BiConsumer<Component, Boolean> show) {
		if (redispatching || !on() || !(message.getContents() instanceof TranslatableContents translatable)
			|| !"chat.disabled.missingProfileKey".equals(translatable.getKey())) {
			return false;
		}
		Component replaced = Component.translatable("flintclient.ncr.chat.disabled.signing_requested").withStyle(message.getStyle());
		if (!onRealms()) {
			current = Level.INSECURE;
		}
		boolean hide = false;
		if (!hideThisSession && !allowSigning()) {
			if (currentModeIs(Mode.ON_DEMAND)) {
				SIGNING_ACTIONS.add(Mc263ChatSafety::resendLastMessage);
				setAllowSigning(true);
				hide = bool(NoChatReports.HIDE_SIGNING_MESSAGE);
			} else if (currentModeIs(Mode.PROMPT)) {
				com.flintclient.client.ui.screen.ChatSafetyScreens.promptSigning();
				hide = bool(NoChatReports.HIDE_SIGNING_MESSAGE);
			}
		}
		if (!hide) {
			redispatching = true;
			try {
				show.accept(replaced, overlay);
			} finally {
				redispatching = false;
			}
		}
		return true;
	}

	/**
	 * A player message's trust level as it shows, or null for Minecraft's own: yours always secure; with the indicators
	 * hidden, unverified and modified messages look secure too. A signed message on a server thought secure means it lets
	 * signed chat through: unintrusive.
	 */
	public static @Nullable ChatTrustLevel trust(PlayerChatMessage message, Component decorated, java.time.Instant received, boolean fromYou) {
		if (!on()) {
			return null;
		}
		debug("Received message: {}, from: {}, signed: {}", decorated.getString(), message.sender(), message.hasSignature());
		if (fromYou) {
			return ChatTrustLevel.SECURE;
		}
		if (message.hasSignature() && current == Level.SECURE) {
			current = Level.UNINTRUSIVE;
		}
		ChatTrustLevel level = ChatTrustLevel.evaluate(message, decorated, received);
		if (level == ChatTrustLevel.NOT_SECURE && bool(NoChatReports.HIDE_INSECURE)
			|| level == ChatTrustLevel.MODIFIED && bool(NoChatReports.HIDE_MODIFIED)) {
			return ChatTrustLevel.SECURE;
		}
		return null;
	}

	/** Chat input: in Always mode the first message waits for signing to start, then goes. True when it's held back. */
	public static boolean chatInput(String normalized) {
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener connection = mc.getConnection();
		if (!enabled() || connection == null || !currentModeIs(Mode.ALWAYS) || allowSigning() || !McCompat.onlineMode(connection)
			|| normalized.isEmpty()) {
			return false;
		}
		current = Level.INSECURE;
		SIGNING_ACTIONS.add(Mc263ChatSafety::resendLastMessage);
		setAllowSigning(true);
		return true;
	}

	public static void lastMessage(String normalized) {
		lastMessage = normalized;
	}

	/** The chat safety button's tooltip for {@code mode}. */
	public static Component safetyTooltip(Mode mode) {
		MutableComponent tooltip = current.tooltip();
		if (allowSigning()) {
			tooltip = Component.translatable("flintclient.ncr.gui.safety_status.insecure_signing");
		} else if (inSingleplayer()) {
			return tooltip;
		}
		String signing = "flintclient.ncr.gui.signing_status.";
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection != null && !McCompat.onlineMode(connection)) {
			signing += "disabled_offline";
		} else if (current == Level.REALMS) {
			signing += "allowed_realms";
		} else if (mode.resolve() == Mode.ALWAYS) {
			signing += allowSigning() ? "allowed" : "disabled_allowance_pending";
		} else if (allowSigning()) {
			signing += "allowed_session";
		} else {
			signing += "disabled";
		}
		tooltip.append("\n\n").append(Component.translatable(signing));
		if (onRealms()) {
			return tooltip;
		}
		tooltip.append("\n\n").append(Component.translatable("flintclient.ncr.gui.safety_status_button.controls"));
		tooltip.append("\n\n").append(Component.translatable("flintclient.ncr.gui.signing_mode",
			mode.title().withStyle(ChatFormatting.BOLD, ChatFormatting.AQUA)));
		tooltip.append("\n").append((mode == Mode.DEFAULT ? mode.resolve() : mode).tooltip());
		return tooltip;
	}

	/** The safety button's choices: the signing modes, or "Cannot Enable" without keys. */
	public static Mode[] safetyChoices() {
		return keysPresent ? new Mode[] {Mode.DEFAULT, Mode.NEVER, Mode.ALWAYS, Mode.PROMPT, Mode.ON_DEMAND} : new Mode[] {Mode.NEVER_FORCED};
	}

	/** The safety button picked {@code mode}: it becomes the current server's. */
	public static void safetyPicked(Mode mode) {
		if (keysPresent && lastServer != null) {
			setMode(lastServer, mode);
		}
	}

	/** The multiplayer menu's NCR button: Enable mod on or off; the state starts over. */
	public static void setEnableMod(boolean value) {
		ModuleState state = Mods.state(MODULE);
		if (state.set(NoChatReports.ENABLE_MOD, value)) {
			ControlState.saveAll();
		}
		reset();
	}

	/** The Realms warning's "Don't show again". */
	public static void setSkipRealmsWarning() {
		if (Mods.state(MODULE).set(NoChatReports.SKIP_REALMS_WARNING, true)) {
			ControlState.saveAll();
		}
	}

	// ---------- the server list ----------

	/** A parsed JSON value: when it's a server status, remember whether it says it prevents chat reports (this thread). */
	public static void parsedJson(@Nullable JsonElement element) {
		if (element == null || !element.isJsonObject() || !on()) {
			return;
		}
		JsonObject object = element.getAsJsonObject();
		if (object.get("version") instanceof JsonObject version && version.has("name") && version.has("protocol")) {
			boolean prevents = object.has("preventsChatReports") && object.get("preventsChatReports").isJsonPrimitive()
				&& object.get("preventsChatReports").getAsBoolean();
			STATUS_PREVENTS.set(prevents);
			debug("Received chat report prevention status from ServerStatus packet: {}", prevents);
		}
	}

	/**
	 * A server list ping answered ({@code listener} is the pinger's listener, which holds the server's entry): whether the server
	 * prevents chat reports, as its status said, or because it predates chat reporting (protocol before 759, 1.19).
	 */
	public static void statusResponse(Object listener, net.minecraft.network.protocol.status.ServerStatus status) {
		if (!on()) {
			return;
		}
		Boolean said = STATUS_PREVENTS.get();
		STATUS_PREVENTS.remove();
		boolean prevents = said != null && said;
		if (status.version().isPresent()) {
			int protocol = status.version().get().protocol();
			if (protocol < 759 && protocol > 0) {
				prevents = true;
			}
		}
		ServerData data = serverData(listener);
		if (data instanceof ServerFlag flag) {
			flag.flint$setPreventsReports(prevents);
			debug("Received status response packet from server, preventsChatReports: {}", prevents);
		}
	}

	/** The server entry a pinger's listener (an anonymous class) captured. */
	private static @Nullable ServerData serverData(Object listener) {
		Optional<Field> field = SERVER_DATA_FIELDS.computeIfAbsent(listener.getClass(), type -> {
			for (Field f : type.getDeclaredFields()) {
				if (f.getType() == ServerData.class) {
					f.setAccessible(true);
					return Optional.of(f);
				}
			}
			return Optional.empty();
		});
		try {
			return field.isPresent() ? (ServerData) field.get().get(listener) : null;
		} catch (IllegalAccessException e) {
			return null;
		}
	}

	// ---------- worlds you host ----------

	/** A packet the world you host sends a player: player chat goes out as a system message (Convert to system messages). */
	public static @Nullable Packet<?> outgoing(Packet<?> packet) {
		if (!(packet instanceof ClientboundPlayerChatPacket chat) || !with(NoChatReports.CONVERT)) {
			return null;
		}
		Component content = McCompat.unsignedContent(chat);
		return new ClientboundSystemChatPacket(chat.chatType().decorate(content != null ? content : Component.literal(chat.body().content())), false);
	}

	/** The world you host ignores chat signatures and chat sessions (so players' chat is never signed through it). */
	public static boolean hostIgnoresSigning() {
		return on();
	}
}
