package com.flintclient.client.module.servers;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The threads the shortcut pings run on: up to {@code threads} at once, as Minecraft's own server list
 * pings five at once, so a slow or dead host only holds up its own slot. The threads are daemons and
 * exit after {@code idleMillis} without a ping, so none stay running between menu visits.
 */
final class PingPool {
	private PingPool() {
	}

	static ThreadPoolExecutor create(int threads, long idleMillis) {
		AtomicInteger created = new AtomicInteger();
		ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, idleMillis, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
			Thread thread = new Thread(runnable, "Flint server status #" + created.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}
}
