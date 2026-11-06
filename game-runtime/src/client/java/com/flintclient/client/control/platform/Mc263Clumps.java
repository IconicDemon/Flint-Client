package com.flintclient.client.control.platform;

import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.mods.ServerMods.Clumps;

/**
 * Clumps' switch, read by the XP orb hooks ({@code mixin.feature.ExperienceOrbMixin}). Orbs live on the server, so the
 * hooks only ever act in the game this client hosts (single player, a LAN world), on its server thread: reading the switch
 * there is a plain field read of the profile in use.
 */
public final class Mc263Clumps {
	private static final int MODULE = Mods.index(Clumps.ID);

	private Mc263Clumps() {
	}

	/** What the orb mixin adds to every XP orb, so one orb can reach another's parts. */
	public interface Orb {
		java.util.Map<Integer, Integer> flint$parts();

		void flint$become(java.util.Map<Integer, Integer> parts);

		int flint$age();

		void flint$setAge(int age);
	}

	public static boolean on() {
		try {
			return Mods.on(MODULE);
		} catch (RuntimeException | LinkageError e) {
			// Settings not readable (still loading): vanilla orbs.
			return false;
		}
	}
}
