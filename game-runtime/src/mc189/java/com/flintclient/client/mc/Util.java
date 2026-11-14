package com.flintclient.client.mc;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Newer Minecraft's clock and workers, for Flint's main menu on Minecraft 1.8.9 (see {@link Component}). */
public final class Util {
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Flint menu worker");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private Util() {
	}

	/** Milliseconds on a steady clock (as newer Minecraft's: not the wall clock). */
	public static long getMillis() {
		return System.nanoTime() / 1_000_000L;
	}

	/** A low-priority worker for loading off the render thread. */
	public static Executor backgroundExecutor() {
		return WORKER;
	}

	public static Executor ioPool() {
		return WORKER;
	}

	public static Executor nonCriticalIoPool() {
		return WORKER;
	}
}
