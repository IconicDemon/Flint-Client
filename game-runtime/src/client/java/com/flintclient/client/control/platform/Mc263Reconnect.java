package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.ReconnectPlan;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of Reconnect. Every server connection starts through {@code ConnectScreen.startConnecting}, which
 * says where to (so a failed attempt at a new server never sends you back to an old one); the disconnected screen then
 * gets a Reconnect button, and with Auto reconnect it counts down and presses it itself, a limited number of times in a
 * row. Clicking the button during a countdown cancels it. Being in a world for half a minute starts the count again.
 * Singleplayer never connects this way, so it is never reconnected to. Render thread only.
 */
public final class Mc263Reconnect {
	private static final int RECONNECT = Mods.index(UtilityMods.Reconnect.ID);
	private static final long STAYED_NS = 30_000_000_000L;
	private static final HookGuard GUARD = new HookGuard("reconnect",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final ReconnectPlan PLAN = new ReconnectPlan();
	private static @Nullable ServerAddress address;
	private static @Nullable ServerData server;
	private static @Nullable Button button;
	private static @Nullable Screen shownOn;
	private static @Nullable Screen parent;
	/** When the countdown ends (nanoTime), or 0 when none runs. */
	private static long dueAt;
	/** When you were first seen in the current world (nanoTime), or 0. */
	private static long inWorldSince;
	/** The seconds the button shows, so its text is only rebuilt when they change. */
	private static long shownSeconds = -1;

	private Mc263Reconnect() {
	}

	/** From {@code ConnectScreen.startConnecting}: where this connection goes. */
	public static void connecting(ServerAddress to, ServerData data) {
		address = to;
		server = data;
	}

	/**
	 * From the disconnected screen's {@code init}: its Reconnect button (placed below {@code belowY}), or null when Reconnect is
	 * off or there is no server to go back to.
	 */
	public static @Nullable Button button(Screen screen, Screen back, int belowY) {
		if (!GUARD.on() || !Mods.on(RECONNECT) || address == null || server == null) {
			button = null;
			return null;
		}
		try {
			boolean fresh = shownOn != screen;
			shownOn = screen;
			parent = back;
			if (fresh) {
				ModuleState reconnect = Mods.state(RECONNECT);
				double delay = reconnect.bool(UtilityMods.Reconnect.AUTO) ? PLAN.nextDelay(reconnect.integer(UtilityMods.Reconnect.ATTEMPTS),
					reconnect.decimal(UtilityMods.Reconnect.DELAY), reconnect.bool(UtilityMods.Reconnect.INCREMENT_DELAY),
					reconnect.decimal(UtilityMods.Reconnect.INCREMENT), reconnect.decimal(UtilityMods.Reconnect.MAX_DELAY)) : -1;
				dueAt = delay < 0 ? 0 : System.nanoTime() + (long) (delay * 1e9);
				if (dueAt == 0 && delay >= 0) {
					dueAt = 1;
				}
			}
			int y = Math.min(belowY + 4, screen.height - 24);
			button = Button.builder(label(), b -> pressed()).bounds(screen.width / 2 - 100, y, 200, 20).build();
			return button;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return null;
		}
	}

	/** From the disconnected screen's {@code repositionElements}: the button kept under its buttons. */
	public static void place(Screen screen, int belowY) {
		Button shown = button;
		if (shown != null && shownOn == screen) {
			shown.setPosition(screen.width / 2 - 100, Math.min(belowY + 4, screen.height - 24));
		}
	}

	private static void pressed() {
		if (dueAt != 0) {
			// A countdown was running: cancel it, and the tries left with it.
			dueAt = 0;
			PLAN.reset();
			if (button != null) {
				button.setMessage(label());
			}
			return;
		}
		connect();
	}

	private static Component label() {
		if (dueAt == 0) {
			return Component.literal("Reconnect");
		}
		shownSeconds = seconds();
		return Component.literal("Reconnecting in " + shownSeconds + " s · Cancel");
	}

	/** Whole seconds left in the countdown, rounded up. */
	private static long seconds() {
		return Math.max(0, (dueAt - System.nanoTime() + 999_999_999L) / 1_000_000_000L);
	}

	private static void connect() {
		ServerAddress to = address;
		ServerData data = server;
		Screen back = parent;
		if (to == null || data == null || back == null) {
			return;
		}
		dueAt = 0;
		button = null;
		shownOn = null;
		ConnectScreen.startConnecting(back, Minecraft.getInstance(), to, data, false, null);
	}

	/** Each client tick: the countdown on the disconnected screen, and starting the count again after a stay in a world. */
	static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null) {
				long now = System.nanoTime();
				if (inWorldSince == 0) {
					inWorldSince = now;
				} else if (now - inWorldSince >= STAYED_NS) {
					PLAN.reset();
				}
				return;
			}
			inWorldSince = 0;
			Button shown = button;
			if (shown == null || mc.gui.screen() != shownOn || dueAt == 0) {
				return;
			}
			if (!Mods.on(RECONNECT)) {
				dueAt = 0;
				shown.setMessage(label());
			} else if (System.nanoTime() - dueAt >= 0) {
				PLAN.tried();
				connect();
			} else if (seconds() != shownSeconds) {
				shown.setMessage(label());
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}
}
