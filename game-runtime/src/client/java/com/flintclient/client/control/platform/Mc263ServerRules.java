package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.ServerRules;
import com.flintclient.client.control.model.ModuleState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Minecraft's side of {@link ServerRules}: where you are, told to it every tick (the server's address and the brand it
 * reports, or your own world), and a short note when a mod you have on is off on this server ("Freelook is off on Hypixel:
 * its rules don't allow it"). Fault-isolated: a failure leaves the last rules in place.
 */
public final class Mc263ServerRules {
	private static final HookGuard GUARD = new HookGuard("server rules",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));

	private Mc263ServerRules() {
	}

	/** Each client tick. */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			boolean inWorld = mc.level != null && mc.player != null;
			ServerData data = mc.getCurrentServer();
			ClientPacketListener connection = mc.getConnection();
			ServerRules.update(inWorld, mc.hasSingleplayerServer(), data == null ? null : data.ip,
				connection == null ? null : connection.serverBrand());
			if (!inWorld) {
				return;
			}
			String note = ServerRules.notice(Mc263ServerRules::turnedOn);
			if (note != null) {
				Mc263NoticeToast.show(note);
				FlintClient.LOGGER.info("Flint: {}", note);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** Turned on by the player (whatever the server allows). */
	private static boolean turnedOn(int index) {
		ModuleState state = Mods.state(index);
		return state.enabled() && state.descriptor().status().usable();
	}
}
