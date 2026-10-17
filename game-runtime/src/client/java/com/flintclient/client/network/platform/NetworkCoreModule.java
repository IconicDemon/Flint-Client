package com.flintclient.client.network.platform;

import com.flintclient.client.network.NetworkCore;
import com.flintclient.client.perf.Compat;
import com.flintclient.client.perf.PerfConfig;
import com.flintclient.client.perf.PerfModule;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * {@code networkCore}: follows each multiplayer connection attempt (see {@link NetworkCore}) and logs one
 * line when it joins and one when it ends. It only observes, so it runs whatever Automatic Optimization
 * says; {@code modules.networkCore: false} switches the hooks off for diagnosis.
 */
public final class NetworkCoreModule implements PerfModule {
	public static final String ID = "networkCore";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean defaultEnabled(PerfConfig config, Compat compat) {
		return true;
	}

	@Override
	public void start() {
		NetworkCore.start(new Mc263NetworkPlatform());
	}

	/** Before its socket exists, the only thing that takes the connect screen away is its Cancel button. */
	@Override
	public void onScreenChanged(@Nullable Screen previous, @Nullable Screen next) {
		if (previous instanceof ConnectScreen && !(next instanceof ConnectScreen)) {
			NetworkHooks.connectScreenClosed();
		}
	}
}
