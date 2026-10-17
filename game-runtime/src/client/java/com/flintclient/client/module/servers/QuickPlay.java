package com.flintclient.client.module.servers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Quick Play: the servers the player pinned to the main menu's left rail, in the order pinned, at most {@link #MAX}.
 * Nothing is pinned until the player pins it (from Multiplayer); joining a server never pins it. A pin is the saved
 * server's name and address only (no account data). Pure rules, version-neutral; the list is immutable and every change
 * returns a new one.
 */
public final class QuickPlay {
	public static final int MAX = 5;
	private static final int MAX_NAME = 64;
	private static final int MAX_ADDRESS = 255;

	/** One pinned server: its name in the server list and its address. */
	public record Entry(String name, String address) {
		public Entry {
			name = name == null ? "" : name.strip();
			address = address == null ? "" : address.strip();
		}
	}

	/** What pinning did. */
	public enum Result {
		ADDED,
		/** That server is already pinned. */
		ALREADY_PINNED,
		/** Five are pinned; one has to be removed first. */
		FULL,
		/** No usable address. */
		INVALID
	}

	/** A change: the result and the list after it (the same list unless {@link Result#ADDED}). */
	public record Change(Result result, List<Entry> list) {
	}

	private QuickPlay() {
	}

	/**
	 * An address as compared: trimmed, lower case, without Minecraft's default port, so {@code "Play.Example.net"} and
	 * {@code "play.example.net:25565"} are the same server.
	 */
	public static String key(String address) {
		String a = address.strip().toLowerCase(Locale.ROOT);
		if (a.endsWith(":25565")) {
			a = a.substring(0, a.length() - ":25565".length());
		}
		if (a.endsWith(".")) {
			a = a.substring(0, a.length() - 1);
		}
		return a;
	}

	/** Whether {@code address} can be pinned: not empty, not too long, no spaces or control characters. */
	public static boolean usable(String address) {
		String a = address.strip();
		if (a.isEmpty() || a.length() > MAX_ADDRESS) {
			return false;
		}
		for (int i = 0; i < a.length(); i++) {
			char ch = a.charAt(i);
			if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
				return false;
			}
		}
		return true;
	}

	public static boolean contains(List<Entry> list, String address) {
		return indexOf(list, address) >= 0;
	}

	public static int indexOf(List<Entry> list, String address) {
		String k = key(address);
		for (int i = 0; i < list.size(); i++) {
			if (key(list.get(i).address()).equals(k)) {
				return i;
			}
		}
		return -1;
	}

	/** Pins a server at the end of the list. Never replaces a pin; at {@link #MAX} it says so instead. */
	public static Change add(List<Entry> list, String name, String address) {
		if (!usable(address)) {
			return new Change(Result.INVALID, list);
		}
		if (contains(list, address)) {
			return new Change(Result.ALREADY_PINNED, list);
		}
		if (list.size() >= MAX) {
			return new Change(Result.FULL, list);
		}
		List<Entry> next = new ArrayList<>(list);
		next.add(new Entry(shortName(name, address), address.strip()));
		return new Change(Result.ADDED, List.copyOf(next));
	}

	/** Unpins {@code address}; the same list when it wasn't pinned. */
	public static List<Entry> remove(List<Entry> list, String address) {
		int i = indexOf(list, address);
		if (i < 0) {
			return list;
		}
		List<Entry> next = new ArrayList<>(list);
		next.remove(i);
		return List.copyOf(next);
	}

	/** A saved server edited from {@code oldAddress} to {@code name} at {@code newAddress}: its pin follows. */
	public static List<Entry> edited(List<Entry> list, String oldAddress, String name, String newAddress) {
		int i = indexOf(list, oldAddress);
		if (i < 0) {
			return list;
		}
		List<Entry> next = new ArrayList<>(list);
		if (!usable(newAddress) || indexOf(list, newAddress) >= 0 && indexOf(list, newAddress) != i) {
			// Edited into nothing usable, or into another pinned server: one pin for it is enough.
			next.remove(i);
		} else {
			next.set(i, new Entry(shortName(name, newAddress), newAddress.strip()));
		}
		return List.copyOf(next);
	}

	/**
	 * The pins against the player's saved servers (read from the server list): a pin whose server is still saved takes
	 * its current name; one whose server is gone is dropped (the server was deleted, or its address changed somewhere
	 * Flint didn't see). Order is kept. The same list when nothing changes.
	 */
	public static List<Entry> reconcile(List<Entry> list, List<Entry> saved) {
		List<Entry> next = new ArrayList<>(list.size());
		boolean changed = false;
		for (Entry pin : list) {
			int i = indexOf(saved, pin.address());
			if (i < 0) {
				changed = true;
				continue;
			}
			Entry now = new Entry(shortName(saved.get(i).name(), pin.address()), pin.address());
			changed |= !now.equals(pin);
			next.add(now);
		}
		return changed ? List.copyOf(next) : list;
	}

	/**
	 * Pins from Flint's older five-slot file: only slots the player had assigned (empty slots were never servers),
	 * without duplicates or unusable addresses, at most {@link #MAX}, in slot order.
	 */
	public static List<Entry> migrate(List<@Nullable Entry> oldSlots) {
		List<Entry> out = new ArrayList<>();
		for (Entry slot : oldSlots) {
			if (slot != null && usable(slot.address()) && !contains(out, slot.address()) && out.size() < MAX) {
				out.add(new Entry(shortName(slot.name(), slot.address()), slot.address().strip()));
			}
		}
		return List.copyOf(out);
	}

	/** The name kept for a pin: the server's name (no formatting codes, at most 64 characters), else its address. */
	static String shortName(String name, String address) {
		StringBuilder out = new StringBuilder();
		String n = name == null ? "" : name;
		for (int i = 0; i < n.length() && out.length() < MAX_NAME; i++) {
			char ch = n.charAt(i);
			if (ch == '§') {
				i++;
				continue;
			}
			if (!Character.isISOControl(ch)) {
				out.append(ch);
			}
		}
		String clean = out.toString().strip();
		return clean.isEmpty() ? address.strip() : clean;
	}

	/**
	 * Up to two letters for a pin with no server icon: the initials of its first two words ("Mineplex Games" → "MG"),
	 * else its first two letters or digits ("hypixel" → "HY"); "?" when there are none.
	 */
	public static String monogram(String name) {
		String[] words = name.strip().split("[\\s._\\-]+");
		StringBuilder out = new StringBuilder();
		if (words.length >= 2) {
			for (String word : words) {
				int cp = firstLetterOrDigit(word);
				if (cp >= 0) {
					out.appendCodePoint(Character.toUpperCase(cp));
				}
				if (out.codePointCount(0, out.length()) == 2) {
					break;
				}
			}
		}
		if (out.isEmpty()) {
			name.strip().codePoints().filter(Character::isLetterOrDigit).limit(2).forEach(cp -> out.appendCodePoint(Character.toUpperCase(cp)));
		}
		return out.isEmpty() ? "?" : out.toString();
	}

	private static int firstLetterOrDigit(String word) {
		return word.codePoints().filter(Character::isLetterOrDigit).findFirst().orElse(-1);
	}
}
