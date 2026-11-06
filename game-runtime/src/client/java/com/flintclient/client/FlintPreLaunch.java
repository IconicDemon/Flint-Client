package com.flintclient.client;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Before the game starts: on a Minecraft version this jar wasn't built for (a newer one the launcher gave Flint's newest
 * runtime, say 26.4 with the 26.3 jar), a Flint mixin that no longer fits Minecraft is skipped with a warning instead of
 * stopping the game. Each feature's own guard then turns off only what can't run, so the menu and everything that still
 * fits keep working. An injection that finds nothing to inject into is skipped quietly in every run ({@code defaultRequire}
 * 0 in Flint's mixin configs): the build-time mixin checkers verify every target on every version Flint ships.
 */
public final class FlintPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		// The Control Center's settings are read on a worker while Minecraft starts, ready for FlintClient's init.
		try {
			com.flintclient.client.control.ControlState.readAhead(FabricLoader.getInstance().getConfigDir());
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: couldn't start reading the settings ahead; they're read at start-up instead", e);
		}
		if (!untested()) {
			return;
		}
		String builtFor = FabricLoader.getInstance().getModContainer(FlintClient.MOD_ID).map(FlintPreLaunch::builtFor).orElse("");
		String running = running();
		FlintClient.LOGGER.warn("Flint: built for Minecraft {}, running on {}; anything that no longer fits is switched off instead of "
			+ "stopping the game", builtFor, running);
		Mixins.registerErrorHandlerClass(SoftMixins.class.getName());
	}

	/** Whether this run is on a Minecraft version the jar wasn't built for. Read by the mixin plugin too, possibly first. */
	public static boolean untested() {
		return Untested.VALUE;
	}

	private static String running() {
		return FabricLoader.getInstance().getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
	}

	private static final class Untested {
		static final boolean VALUE = compute();

		private static boolean compute() {
			String builtFor = FabricLoader.getInstance().getModContainer(FlintClient.MOD_ID).map(FlintPreLaunch::builtFor).orElse("");
			String running = running();
			return !builtFor.isEmpty() && !running.isEmpty() && !running.equals(builtFor) && !running.startsWith(builtFor + ".");
		}
	}

	private static String builtFor(ModContainer flint) {
		var value = flint.getMetadata().getCustomValue("flintclient:builtFor");
		return value == null ? "" : value.getAsString();
	}

	/** Flint's mixins only: a failure becomes a warning (the mixin is skipped). Anything else keeps Mixin's own action. */
	public static final class SoftMixins implements IMixinErrorHandler {
		@Override
		public ErrorAction onPrepareError(IMixinConfig config, Throwable error, IMixinInfo mixin, ErrorAction action) {
			return flint(config) ? warn(mixin, error) : action;
		}

		@Override
		public ErrorAction onApplyError(String target, Throwable error, IMixinInfo mixin, ErrorAction action) {
			return mixin != null && flint(mixin.getConfig()) ? warn(mixin, error) : action;
		}

		private static boolean flint(IMixinConfig config) {
			return config != null && config.getName().startsWith(FlintClient.MOD_ID + ".");
		}

		private static ErrorAction warn(IMixinInfo mixin, Throwable error) {
			FlintClient.LOGGER.warn("Flint: {} doesn't fit this Minecraft and is skipped ({})", mixin == null ? "a mixin" : mixin.getName(),
				error.toString());
			return ErrorAction.WARN;
		}
	}
}
