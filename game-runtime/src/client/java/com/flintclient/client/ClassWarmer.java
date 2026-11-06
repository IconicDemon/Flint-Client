package com.flintclient.client;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads Flint's own classes of a few packages on a worker thread, without initialising them, so the render thread finds
 * them ready when a screen first needs them: the Right Shift menu's first opening otherwise reads, transforms and defines
 * a few hundred classes between the key and its first frame. Loading never runs a class's static code, so it is safe beside
 * the game; Mixin's own classes are never touched, and a class that can't load here is simply left for its first use.
 * Reads Flint's own jar only (nothing when running from folders, as in development). Platform-free (1.8.9 shares it).
 */
public final class ClassWarmer {
	private ClassWarmer() {
	}

	/**
	 * Loads every class in Flint's jar (the one {@code anchor} came from) whose path starts with one of {@code prefixes}
	 * ({@code "com/flintclient/client/control/rshift/"}); returns how many loaded. Call it on a worker.
	 */
	public static int load(Class<?> anchor, List<String> prefixes) {
		ClassLoader loader = anchor.getClassLoader();
		List<String> names = new ArrayList<>();
		try {
			URI where = anchor.getProtectionDomain().getCodeSource().getLocation().toURI();
			File jar = new File(where);
			if (!jar.isFile()) {
				return 0;
			}
			try (ZipFile zip = new ZipFile(jar)) {
				for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
					String path = entries.nextElement().getName();
					if (path.endsWith(".class") && !path.contains("/mixin/") && prefixes.stream().anyMatch(path::startsWith)) {
						names.add(path.substring(0, path.length() - ".class".length()).replace('/', '.'));
					}
				}
			}
		} catch (Exception | LinkageError e) {
			FlintLog.LOGGER.debug("Flint: couldn't list its classes to load ahead", e);
			return 0;
		}
		int loaded = 0;
		for (String name : names) {
			try {
				Class.forName(name, false, loader);
				loaded++;
			} catch (Throwable e) {
				// Left for its first use, which reports it as it always did.
			}
		}
		return loaded;
	}
}
