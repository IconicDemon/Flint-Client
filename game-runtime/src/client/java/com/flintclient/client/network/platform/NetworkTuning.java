package com.flintclient.client.network.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.perf.Compat;
import com.flintclient.client.perf.PerfConfig;
import com.flintclient.client.perf.PerfModule;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;

/**
 * {@code networkTuning}: the game connection to a server, once open, is marked as low-delay traffic (IP type of service
 * {@value #LOW_DELAY}, "minimise delay"), so a router, Windows' QoS or a gaming network card that honours the mark sends
 * Flint's small game packets ahead of bulk downloads sharing the line. Minecraft already turns off Nagle's algorithm
 * (TCP_NODELAY), so movement and clicks leave at once; Flint checks that it is still off. Neither shortens the route to the
 * server, which is what ping mostly measures: this only removes delay the computer and the local network can add. Runs
 * with Automatic Optimization; a socket that refuses an option keeps Minecraft's own. Netty thread.
 */
public final class NetworkTuning implements PerfModule {
	public static final String ID = "networkTuning";
	/** IPTOS_LOWDELAY. */
	static final int LOW_DELAY = 0x10;
	private static volatile boolean enabled;
	private static volatile boolean warned;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean defaultEnabled(PerfConfig config, Compat compat) {
		return config.automaticOptimization();
	}

	@Override
	public void start() {
		enabled = true;
	}

	/** Called with the future of a connection to a server: tunes its socket once it is open. */
	public static void socketOpening(ChannelFuture future) {
		if (!enabled) {
			return;
		}
		future.addListener((ChannelFutureListener) done -> {
			if (done.isSuccess()) {
				tune(done.channel());
			}
		});
	}

	private static void tune(Channel channel) {
		try {
			channel.config().setOption(ChannelOption.TCP_NODELAY, true);
			channel.config().setOption(ChannelOption.IP_TOS, LOW_DELAY);
		} catch (RuntimeException | LinkageError e) {
			if (!warned) {
				warned = true;
				FlintClient.LOGGER.info("Flint: the connection keeps Minecraft's own socket settings ({})", e.toString());
			}
		}
	}
}
