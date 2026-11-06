package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.OptionSetting;
import java.util.List;

/**
 * Modules about what a server does or sees: Clumps (XP orbs in the world you host) and No Chat Reports (what your client
 * sends and how chat safety shows).
 */
public final class ServerMods {
	private ServerMods() {
	}

	/** Clumps (Jaredlll08, MIT): it has no options of its own, so the module's switch is all there is. */
	public static final class Clumps {
		public static final String ID = "clumps";
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Clumps", "Clumps XP orbs together")
			.keywords("xp", "experience", "orbs", "merge", "lag", "clumps").icon(IconId.GAUGE).category(Category.WORLD)
			.note("XP orbs that come near each other merge into one orb worth all of them, and touching an orb takes it at once, "
				+ "mending included. Orbs are the server's, so this works in single player and in the world you open to LAN; on a "
				+ "multiplayer server it does nothing (the server decides). Orbs merged while it's on stay correct when you turn it "
				+ "off.").build();
	}

	/**
	 * No Chat Reports (Aizistral, WTFPL): its client options (NCR-Client.json), its debug log, and "convert to game message"
	 * for the world this client hosts. The dedicated-server options (demandOnClient, its kick message, addQueryData) have no
	 * place on a client.
	 */
	public static final class NoChatReports {
		public static final String ID = "no_chat_reports";
		public static final BoolSetting ENABLE_MOD = new BoolSetting("enableMod", "Enable mod",
			"Most of what it does on your client (signing, safety status, the secure chat option); the multiplayer menu's NCR button "
				+ "flips it. Takes full effect when you next join a server", true);
		public static final OptionSetting DEFAULT_MODE = new OptionSetting("defaultSigningMode", "Default signing mode",
			"For servers that ask for or allow signed chat; each server can have its own (the safety button in chat)", 2, "never", "Never",
			"always", "Always", "prompt", "Prompt", "on_demand", "On Demand");
		public static final BoolSetting DEMAND_ON_SERVER = new BoolSetting("demandOnServer", "Demand on server",
			"Leave servers that can't be shown to protect your chat", false);
		public static final BoolSetting SHOW_SAFETY = new BoolSetting("showServerSafety", "Show server safety",
			"The server's safety status in the bottom-right corner of the chat screen; hover it for details, click it to pick the "
				+ "server's signing mode", true);
		public static final BoolSetting HIDE_INSECURE = new BoolSetting("hideInsecureMessageIndicators", "Hide unverified indicators",
			"No grey bar to the left of unverified chat messages", true);
		public static final BoolSetting HIDE_MODIFIED = new BoolSetting("hideModifiedMessageIndicators", "Hide modified indicators",
			"No grey bar and (?) for chat messages the server modified", true);
		public static final BoolSetting HIDE_SYSTEM = new BoolSetting("hideSystemMessageIndicators", "Hide system indicators",
			"No grey bar to the left of system messages", true);
		public static final BoolSetting HIDE_TOAST = new BoolSetting("hideWarningToast", "Hide warning toast",
			"No \"Chat messages can't be verified\" toast on servers with enforce-secure-profile off", true);
		public static final BoolSetting HIDE_SIGNING_MESSAGE = new BoolSetting("hideSigningRequestMessage", "Hide signing request message",
			"No chat message when the server asks your client to sign", false);
		public static final BoolSetting VERIFIED_ICON = new BoolSetting("verifiedIconEnabled", "Verified icon",
			"A \"Safe Server\" icon in the server list for servers that have No Chat Reports or prevent reports another known way", true);
		public static final IntSetting ICON_X = new IntSetting("verifiedIconOffsetX", "Verified icon X offset", null, 0, -100, 100, 1, " px");
		public static final IntSetting ICON_Y = new IntSetting("verifiedIconOffsetY", "Verified icon Y offset", null, 0, -100, 100, 1, " px");
		public static final BoolSetting SHOW_NCR_BUTTON = new BoolSetting("showNCRButton", "Show NCR button",
			"A button in the multiplayer menu that turns Enable mod on and off", true);
		public static final BoolSetting SHOW_RELOAD_BUTTON = new BoolSetting("showReloadButton", "Show reload button",
			"A button in the multiplayer menu that reloads the servers' signing modes from their file", true);
		public static final BoolSetting ALWAYS_HIDE_REPORT = new BoolSetting("alwaysHideReportButton", "Always hide report button",
			"Never a report button in Social Interactions (otherwise it's only made inactive, with a note, on safe servers)", false);
		public static final BoolSetting SKIP_REALMS_WARNING = new BoolSetting("skipRealmsWarning", "Skip Realms warning",
			"No warning before Realms opens (the warning's \"Don't show again\" sets this)", false);
		public static final BoolSetting DISABLE_TELEMETRY = new BoolSetting("disableTelemetry", "Disable telemetry",
			"Minecraft sends no telemetry", true);
		public static final BoolSetting REMOVE_TELEMETRY_BUTTON = new BoolSetting("removeTelemetryButton", "Remove telemetry button",
			"No Telemetry Data button in Options while telemetry is off", true);
		public static final BoolSetting CONVERT = new BoolSetting("convertToGameMessage", "Convert to system messages",
			"In worlds you host (single player, LAN): player chat goes out as system messages, which carry no signature and can't be "
				+ "reported; your world ignores the signatures players send", true);
		public static final BoolSetting DEBUG_LOG = new BoolSetting("enableDebugLog", "Debug log",
			"Connection, safety and message details in the game log", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "No Chat Reports",
				"Keep your chat messages unsigned so they can't be reported")
			.keywords("chat", "reports", "signing", "signature", "secure", "privacy", "telemetry", "ncr").icon(IconId.SHIELD).category(Category.UTILITY)
			.section("General", ENABLE_MOD, DEFAULT_MODE, DEMAND_ON_SERVER)
			.section("Chat", SHOW_SAFETY, HIDE_INSECURE, HIDE_MODIFIED, HIDE_SYSTEM, HIDE_TOAST, HIDE_SIGNING_MESSAGE)
			.section("Server List", VERIFIED_ICON, ICON_X, ICON_Y, SHOW_NCR_BUTTON, SHOW_RELOAD_BUTTON)
			.section("Privacy", ALWAYS_HIDE_REPORT, SKIP_REALMS_WARNING, DISABLE_TELEMETRY, REMOVE_TELEMETRY_BUTTON)
			.section("Worlds You Host", CONVERT)
			.advanced("Advanced", DEBUG_LOG)
			.note("Your client keeps its chat signing key to itself, so the messages you send carry no signature and can't be reported to "
				+ "Mojang, wherever the server allows unsigned chat; where it insists, your signing mode decides (Prompt asks first). The "
				+ "safety status in chat shows what the server does. Offline-mode servers never sign. The parts of No Chat Reports that "
				+ "only a dedicated server runs (requiring it of joining players, its kick message, the status field other clients read) "
				+ "aren't here; your own worlds get the conversion above.").build();
	}

	public static List<ModuleDescriptor> all() {
		return List.of(Clumps.DESCRIPTOR, NoChatReports.DESCRIPTOR);
	}
}
