package com.flintclient.qa;

import com.mojang.realmsclient.RealmsMainScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

/**
 * Screens and states for tours of the No Chat Reports module: {@code call com.flintclient.qa.QaNcr.addServer NAME ADDRESS},
 * {@code QaNcr.multiplayer}, {@code QaNcr.realms}, {@code QaNcr.options}, {@code QaNcr.chatOptions}, {@code QaNcr.state}.
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
			list.add(new ServerData(name, address, ServerData.Type.OTHER), false);
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
		QaGame.setScreen(mc, optionsScreen(mc));
		return "options";
	}

	/**
	 * Minecraft's Options screen over the current one. Its constructor differs between versions (26.2 and older add an
	 * "in a world" flag after the options), so it is found by its parameters and any extra flag is false.
	 */
	private static net.minecraft.client.gui.screens.Screen optionsScreen(Minecraft mc) {
		for (java.lang.reflect.Constructor<?> c : net.minecraft.client.gui.screens.options.OptionsScreen.class.getConstructors()) {
			Class<?>[] types = c.getParameterTypes();
			if (types.length >= 2 && types[1] == net.minecraft.client.Options.class) {
				Object[] args = new Object[types.length];
				args[0] = QaGame.screen(mc);
				args[1] = mc.options;
				for (int i = 2; i < types.length; i++) {
					args[i] = types[i] == boolean.class ? Boolean.FALSE : null;
				}
				try {
					return (net.minecraft.client.gui.screens.Screen) c.newInstance(args);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("Options screen", e);
				}
			}
		}
		throw new IllegalStateException("no Options screen constructor taking the options");
	}

	public static String chatOptions() {
		Minecraft mc = Minecraft.getInstance();
		QaGame.setScreen(mc, new net.minecraft.client.gui.screens.options.ChatOptionsScreen(QaGame.screen(mc), mc.options));
		return "chat options";
	}

	/** Whether Minecraft's key manager looks empty, whether telemetry is allowed, and Only Show Secure Chat's value. */
	public static String state() {
		Minecraft mc = Minecraft.getInstance();
		return "keysHidden=" + (mc.getProfileKeyPairManager() == ProfileKeyPairManager.EMPTY_KEY_MANAGER) + " telemetry=" + mc.allowsTelemetry()
			+ " secureChatOnly=" + mc.options.onlyShowSecureChat().get();
	}
}
