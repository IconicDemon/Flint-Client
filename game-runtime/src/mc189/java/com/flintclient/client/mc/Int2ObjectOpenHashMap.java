package com.flintclient.client.mc;

import java.util.HashMap;

/**
 * fastutil's int-keyed map as the shared screenshot list uses it ({@code module.screenshots.Thumbnails}), for Minecraft 1.8.9,
 * which ships no fastutil: a plain map with boxed keys (a page of thumbnails holds a few dozen).
 */
public final class Int2ObjectOpenHashMap<V> extends HashMap<Integer, V> {
	private static final long serialVersionUID = 1L;
}
