package com.flintclient.client.control.platform;

import java.util.Arrays;

/**
 * Adds Flint's key bindings to Minecraft's list of them ({@code Options.keyMappings}) without disturbing
 * anyone else's. Fabric API's key binding module rewrites the same list at the same moment (the start of
 * {@code Options.load}) by copying it, removing its own entries and appending them again, so whichever of us
 * runs first, the other keeps its entries. Entries are compared by identity, as both Minecraft's and
 * Fabric's lists do. Pure logic, so it's tested without the game.
 */
public final class MappingList {
	private MappingList() {
	}

	/**
	 * {@code current} with each of {@code additions} that isn't already in it appended, in order. When every
	 * one is already there the same array comes back, so running it again (options can be loaded more than
	 * once) changes nothing.
	 */
	public static <T> T[] appendMissing(T[] current, T[] additions) {
		int missing = 0;
		for (T addition : additions) {
			if (!contains(current, addition)) {
				missing++;
			}
		}
		if (missing == 0) {
			return current;
		}
		T[] merged = Arrays.copyOf(current, current.length + missing);
		int next = current.length;
		for (T addition : additions) {
			if (!contains(current, addition)) {
				merged[next++] = addition;
			}
		}
		return merged;
	}

	private static boolean contains(Object[] array, Object value) {
		for (Object element : array) {
			if (element == value) {
				return true;
			}
		}
		return false;
	}
}
