package com.flintclient.client.compat.net;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.WarningScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.realms.RealmsScreen;
import org.jspecify.annotations.Nullable;

/**
 * No Chat Reports' signing the way Minecraft 1.19.2 signs. 1.19.2 has no chat sessions (1.19.3 brought them): the client gives a
 * server its public key in the login hello and nowhere else, and the server checks each message against the key it got then (a
 * player who gave one and then sends an unsigned message is kicked for "unsigned chat"). So whether a connection signs is decided
 * once, as it begins ({@link #begin}), and kept for all of it ({@link #hidden}); signing allowed in the middle of a session takes
 * effect from the next join.
 *
 * <p>A server that insists on signed chat turns a client without a key away at login ("Missing profile public key"), where 26.x's
 * server refuses an unsigned message instead. There the signing mode decides ({@link #turnedAway}): Always gave the key already
 * (unless your keys were still being read as the connection began: then it joins again at once), Never (and "Cannot Enable",
 * without keys) leaves Minecraft's own screen, Prompt asks first (No Chat Reports' "signing requested" warning) and On Demand
 * doesn't; both join again with signing allowed for that one session.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
public final class LoginSigning {
	private static final String MISSING_KEY = "multiplayer.disconnect.missing_public_key";
	/** The key state of the connection under way (true: hidden), or null when there is none (it follows the setting then). */
	private static volatile @Nullable Boolean hidden;
	/** The server the next connection signs on, once: the one that turned the last try away (main thread). */
	private static @Nullable ServerAddress allowNext;

	private LoginSigning() {
	}

	/**
	 * The connect screen starts a connection (main thread, after the last world was left and before Minecraft asks its key
	 * manager for the hello's key): chat safety starts over for this server, and the connection signs when the server's mode is
	 * Always or the server turned the last try away for want of a key.
	 */
	public static void connecting(ServerAddress address, @Nullable ServerData data) {
		boolean allowed = address.equals(allowNext);
		allowNext = null;
		Mc263ChatSafety.connecting(address, data);
		if (Mc263ChatSafety.enabled() && (allowed || Mc263ChatSafety.mode(address) == Mc263ChatSafety.Mode.ALWAYS)) {
			Mc263ChatSafety.setAllowSigning(true);
		}
		begin();
	}

	/** A connection begins (a server, a Realm or a world of your own): whether it signs is decided now, for all of it. */
	public static void begin() {
		hidden = Mc263ChatSafety.hideKeys();
	}

	/** The world was left: until the next connection begins, the key manager follows the setting. */
	public static void end() {
		hidden = null;
	}

	/** Whether Minecraft's key manager should look empty now (any thread). */
	public static boolean hidden() {
		Boolean latched = hidden;
		return latched != null ? latched : Mc263ChatSafety.hideKeys();
	}

	/**
	 * The login was refused (main thread, as Minecraft is about to show the disconnected screen): when it was for want of a key
	 * and the server's mode isn't Never (or "Cannot Enable", which resolves to it), No Chat Reports takes over and the screen
	 * isn't shown. True when it did. A join that gave the key already (signing allowed) is never taken over: no loop.
	 */
	public static boolean turnedAway(@Nullable Component reason, @Nullable Screen parent) {
		try {
			if (reason == null || !(reason.getContents() instanceof TranslatableContents key) || !MISSING_KEY.equals(key.getKey())
				|| !Mc263ChatSafety.enabled() || Mc263ChatSafety.onRealms() || Mc263ChatSafety.allowSigning() || parent instanceof RealmsScreen) {
				return false;
			}
			ServerAddress address = Mc263ChatSafety.lastServer();
			if (address == null) {
				return false;
			}
			Mc263ChatSafety.Mode mode = Mc263ChatSafety.mode(address);
			if (mode == Mc263ChatSafety.Mode.NEVER) {
				return false;
			}
			Minecraft mc = Minecraft.getInstance();
			ServerData data = mc.getCurrentServer();
			Screen back = parent != null ? parent : new JoinMultiplayerScreen(new TitleScreen());
			if (Mc263ChatSafety.with(NoChatReports.DEBUG_LOG)) {
				FlintClient.LOGGER.info("[No Chat Reports] Server turned the login away for want of a signing key, signing mode: {}", mode);
			}
			if (mode == Mc263ChatSafety.Mode.PROMPT) {
				mc.setScreen(new SigningRequired(back, reason, address, data));
			} else {
				// On Demand (or Always, when the keys were still being read as the connection began): join again, signing. Next
				// frame, before the connect screen ticks again (it would find its connection closed a second time).
				mc.tell(() -> joinSigning(back, address, data));
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: No Chat Reports couldn't handle the refused login; Minecraft's screen shows", e);
			return false;
		}
	}

	/** Joins {@code address} again, this time with your key (signing allowed for that session). */
	static void joinSigning(Screen back, ServerAddress address, @Nullable ServerData data) {
		allowNext = address;
		ConnectScreen.startConnecting(back, Minecraft.getInstance(), address, data);
	}

	/** Prompt mode: the server wants signed chat; ask. Allow joins again signing, Dismiss shows why the login was refused. */
	static final class SigningRequired extends WarningScreen {
		private static final Component TITLE = Component.translatable("flintclient.ncr.gui.signing_required.header").withStyle(ChatFormatting.BOLD);
		private static final Component CONTENT = Component.translatable("flintclient.ncr.gui.signing_required.contents");
		private final Screen back;
		private final Component reason;
		private final ServerAddress address;
		private final @Nullable ServerData data;

		SigningRequired(Screen back, Component reason, ServerAddress address, @Nullable ServerData data) {
			super(TITLE, CONTENT, TITLE.copy().append("\n").append(CONTENT));
			this.back = back;
			this.reason = reason;
			this.address = address;
			this.data = data;
		}

		@Override
		protected void initButtons(int textHeight) {
			addRenderableWidget(new Button(width / 2 - 155, 100 + textHeight, 150, 20,
				Component.translatable("flintclient.ncr.gui.signing_required.allow_signing"), button -> joinSigning(back, address, data)));
			addRenderableWidget(new Button(width / 2 + 5, 100 + textHeight, 150, 20, Component.translatable("flintclient.ncr.gui.signing_required.cancel"),
				button -> minecraft.setScreen(new DisconnectedScreen(back, CommonComponents.CONNECT_FAILED, reason))));
		}

		@Override
		public boolean shouldCloseOnEsc() {
			return false;
		}
	}
}
