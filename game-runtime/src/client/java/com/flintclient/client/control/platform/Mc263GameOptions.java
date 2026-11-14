package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.perf.GpuPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of Flint's game options (the General tab): the Leave server key and its confirmation (which the
 * pause menu's Disconnect button asks too, through {@code PauseScreenLeaveMixin}), and the inactive and idle frame-rate
 * limits ({@code FramerateLimitTracker}, which only ever lowers Minecraft's own limit). The scroll and bobbing options are
 * read straight from their mixins. Fault-isolated; the per-frame limit is allocation-free.
 */
public final class Mc263GameOptions {
	private static final HookGuard GUARD = new HookGuard("game options",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static boolean leaveWas;

	private Mc263GameOptions() {
	}

	/**
	 * Each client tick: Smooth screen sharing as switched, while the game is in a window (in fullscreen the game keeps its normal
	 * GPU priority: measured, a capture of a monitor-covering game got worse with it); Borderless fullscreen kept in step with
	 * Minecraft's Exclusive Fullscreen (26.x); field checks unless something changed. The Leave server key.
	 */
	static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			GpuPriority.apply(ControlState.settings().gameBool(GameOptions.SMOOTH_SHARING) && !mc.options.fullscreen().get());
			FullscreenSync.tick(mc);
			boolean down = Mc263Features.gameplay() && Mc263Features.keyHeld(ControlState.settings().game(GameOptions.LEAVE_KEY));
			if (down && !leaveWas) {
				leave(mc, null);
			}
			leaveWas = down;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** Whether leaving now asks first: Server leave confirmation is on and you are on a server (not your own world). */
	public static boolean confirmsLeaving() {
		Minecraft mc = Minecraft.getInstance();
		return GUARD.on() && ControlState.settings().gameBool(GameOptions.LEAVE_CONFIRM) && !mc.isLocalServer() && mc.getCurrentServer() != null;
	}

	/**
	 * Leaves the world as the pause menu's Disconnect does (a report draft is offered first, as there), asking first when
	 * {@link #confirmsLeaving()}; {@code back} is where No (or keeping a draft) returns, null for the game.
	 */
	public static void leave(Minecraft mc, @Nullable Screen back) {
		Runnable go = () -> mc.getReportingContext().draftReportHandled(mc, back, () -> mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE),
			true);
		if (!confirmsLeaving()) {
			go.run();
			return;
		}
		ServerData server = mc.getCurrentServer();
		mc.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				go.run();
			} else {
				mc.gui.setScreen(back);
			}
		}, Component.literal("Leave the server?"), Component.literal(server == null ? "" : server.name)));
	}

	/**
	 * From {@code FramerateLimitTracker.getFramerateLimit}: Minecraft's limit, or the Inactive FPS when it is lower and the
	 * window is unfocused or there was no input since {@code latestInputMs} for the idle time (each when its option is on).
	 */
	public static int framerateLimit(int vanilla, long latestInputMs) {
		// Instant Swap's standby game draws little until it takes over (once a second at its menu): the player's game keeps the GPU.
		int swapCap = com.flintclient.client.module.social.InstantSwap.frameCap();
		if (swapCap > 0) {
			return Math.min(vanilla, swapCap);
		}
		if (!GUARD.on()) {
			return vanilla;
		}
		try {
			ControlSettings settings = ControlState.settings();
			boolean unfocused = settings.gameBool(GameOptions.LIMIT_UNFOCUSED) && !Minecraft.getInstance().isWindowActive();
			boolean idle = settings.gameBool(GameOptions.LIMIT_IDLE) && Util.getMillis() - latestInputMs >= GameOptions.idleMillis(
				settings.gameInt(GameOptions.IDLE_MINUTES), settings.gameInt(GameOptions.IDLE_SECONDS));
			return unfocused || idle ? Math.min(vanilla, settings.gameInt(GameOptions.INACTIVE_FPS)) : vanilla;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return vanilla;
		}
	}
}
