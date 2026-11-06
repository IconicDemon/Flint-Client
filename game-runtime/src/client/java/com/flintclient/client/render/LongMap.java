package com.flintclient.client.render;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A map from {@code long} keys to values without boxing, so a lookup every frame allocates nothing: open addressing with
 * linear probing over the keys, the values kept in insertion order (which {@link #values()} iterates). Only what the mask
 * atlas's layout needs: get, put, clear, size and the values. Not thread-safe.
 */
final class LongMap<V> {
	private long[] keys = new long[16];
	private int[] slots = new int[16];
	private Object[] values = new Object[8];
	private long[] order = new long[8];
	private int size;

	LongMap() {
		Arrays.fill(slots, -1);
	}

	private static int hash(long key) {
		long h = key * 0x9E3779B97F4A7C15L;
		return (int) (h ^ (h >>> 32));
	}

	private int find(long key) {
		int mask = slots.length - 1;
		for (int i = hash(key) & mask; ; i = (i + 1) & mask) {
			int at = slots[i];
			if (at < 0 || keys[i] == key) {
				return i;
			}
		}
	}

	@SuppressWarnings("unchecked")
	@Nullable V get(long key) {
		int at = slots[find(key)];
		return at < 0 ? null : (V) values[at];
	}

	void put(long key, V value) {
		int i = find(key);
		if (slots[i] >= 0) {
			values[slots[i]] = value;
			return;
		}
		if (size == values.length) {
			values = Arrays.copyOf(values, size * 2);
			order = Arrays.copyOf(order, size * 2);
		}
		keys[i] = key;
		slots[i] = size;
		values[size] = value;
		order[size] = key;
		size++;
		if (size * 2 > slots.length) {
			rehash(slots.length * 2);
		}
	}

	private void rehash(int capacity) {
		keys = new long[capacity];
		slots = new int[capacity];
		Arrays.fill(slots, -1);
		for (int n = 0; n < size; n++) {
			int i = find(order[n]);
			keys[i] = order[n];
			slots[i] = n;
		}
	}

	void clear() {
		Arrays.fill(slots, -1);
		Arrays.fill(values, 0, size, null);
		size = 0;
	}

	int size() {
		return size;
	}

	/** The values, in the order they were first put (a live view). */
	List<V> values() {
		return new AbstractList<>() {
			@Override
			@SuppressWarnings("unchecked")
			public V get(int index) {
				return (V) values[index];
			}

			@Override
			public int size() {
				return size;
			}
		};
	}
}
