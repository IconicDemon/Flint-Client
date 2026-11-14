package com.flintclient.qa;

import com.mojang.authlib.minecraft.TelemetrySession;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.realmsclient.RealmsMainScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatOptionsScreen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

/**
 * Screens and states for tours of the No Chat Reports module: {@code call com.flintclient.qa.QaNcr.addServer NAME ADDRESS},
 * {@code QaNcr.multiplayer}, {@code QaNcr.realms}, {@code QaNcr.options}, {@code QaNcr.chatOptions}, {@code QaNcr.state}.
 * Minecraft 1.19.2 (and 1.19): its options screens are in gui.screens, a saved server is a name, an address and a LAN flag.
 * Dev-only, like the rest of this mod.
 */
public final class QaNcr {
	private QaNcr() {
	}

	/** Adds a server to the saved list (once). */
	public static String addServer(String name, String address) {
		Minecraft mc = Minecraft.getInstance();
		ServerList list = new ServerList(mc);
		list.load();
		if (list.get(address) == null) {
			list.add(new ServerData(name, address, false), false);
			list.save();
		}
		return "servers " + list.size();
	}

	public static String multiplayer() {
		Minecraft mc = Minecraft.getInstance();
		QaGame.setScreen(mc, new JoinMultiplayerScreen(QaGame.screen(mc)));
		return "multiplayer";
	}

	public static String realms() {
		Minecraft mc = Minecraft.getInstance();
		QaGame.setScreen(mc, new RealmsMainScreen(QaGame.screen(mc)));
		return "realms -> " + QaGame.screen(mc).getClass().getSimpleName();
	}

	public static String options() {
		Minecraft mc = Minecraft.getInstance();
		QaGame.setScreen(mc, new OptionsScreen(QaGame.screen(mc), mc.options));
		return "options";
	}

	public static String chatOptions() {
		Minecraft mc = Minecraft.getInstance();
		QaGame.setScreen(mc, new ChatOptionsScreen(QaGame.screen(mc), mc.options));
		return "chat options";
	}

	/**
	 * Whether Minecraft's key manager looks empty, whether telemetry is allowed, and Only Show Secure Chat's value. 1.19.2 has
	 * no empty key manager of its own (every user gets a real one, which an offline player's finds no keys for): it looks
	 * empty when the one Minecraft hands out isn't its own (Flint's keyless stand-in). It has no telemetry switch either: a
	 * telemetry session the user API service starts now is on or off (No Chat Reports makes it the disabled one).
	 */
	public static String state() throws ReflectiveOperationException {
		Minecraft mc = Minecraft.getInstance();
		boolean keysHidden = mc.getProfileKeyPairManager().getClass() != ProfileKeyPairManager.class;
		UserApiService api = (UserApiService) QaNames.field(Minecraft.class, "userApiService").get(mc);
		TelemetrySession telemetry = api.newTelemetrySession(Runnable::run);
		return "keysHidden=" + keysHidden + " telemetry=" + telemetry.isEnabled() + " secureChatOnly=" + mc.options.onlyShowSecureChat().get();
	}
}
