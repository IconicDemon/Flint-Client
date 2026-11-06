package com.flintclient.client.control.feature;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Nametags' chat bubbles, version-neutral: each player's recent chat messages with when they came, how strongly each
 * shows as it fades in and out, and who sent a chat line a server turned into plain text ("&lt;Steve&gt; hi",
 * "[VIP] Steve: hi", "Steve » hi"). Times are in milliseconds. Pure Java; render thread only.
 */
public final class ChatBubbles {
	/** The most messages kept per player (the most a player can choose to show). */
	public static final int KEPT = 8;

	private final Map<UUID, ArrayDeque<Message>> messages = new HashMap<>();

	/** One shown message: its text and how strongly it shows now (0..1). */
	public record Line(String text, float alpha) {
	}

	private record Message(String text, long at) {
	}

	/** {@code player} said {@code text} at {@code now}: cut to {@code maxChars} (with "…"), newest last. */
	public void add(UUID player, String text, long now, int maxChars) {
		String line = cut(text.strip().replaceAll("\\s+", " "), maxChars);
		if (line.isEmpty()) {
			return;
		}
		ArrayDeque<Message> queue = messages.computeIfAbsent(player, k -> new ArrayDeque<>());
		queue.addLast(new Message(line, now));
		while (queue.size() > KEPT) {
			queue.removeFirst();
		}
	}

	/**
	 * {@code player}'s messages to show at {@code now}, newest first, at most {@code max}: each fades in over
	 * {@code fadeIn}, stays until {@code delay} after it came, then fades out over {@code fadeOut}. Gone ones are dropped.
	 */
	public List<Line> lines(UUID player, long now, int max, long fadeIn, long delay, long fadeOut) {
		ArrayDeque<Message> queue = messages.get(player);
		if (queue == null) {
			return List.of();
		}
		List<Line> out = new ArrayList<>(Math.min(max, queue.size()));
		for (Iterator<Message> it = queue.descendingIterator(); it.hasNext(); ) {
			Message m = it.next();
			long age = now - m.at();
			if (age > delay + fadeOut) {
				it.remove();
				continue;
			}
			if (out.size() < max) {
				out.add(new Line(m.text(), alpha(age, fadeIn, delay, fadeOut)));
			}
		}
		if (queue.isEmpty()) {
			messages.remove(player);
		}
		return out;
	}

	/** How strongly a message {@code age} old shows (0..1). */
	public static float alpha(long age, long fadeIn, long delay, long fadeOut) {
		if (age < 0) {
			return 0;
		}
		float in = fadeIn <= 0 ? 1 : Math.min(1F, age / (float) fadeIn);
		float out = age <= delay ? 1 : fadeOut <= 0 ? 0 : Math.max(0F, 1 - (age - delay) / (float) fadeOut);
		return Math.min(in, out);
	}

	/** Forgets messages older than {@code maxAge}, of players no longer drawn too. */
	public void prune(long now, long maxAge) {
		messages.values().removeIf(queue -> {
			queue.removeIf(m -> now - m.at() > maxAge);
			return queue.isEmpty();
		});
	}

	/** Whether no message is kept. */
	public boolean isEmpty() {
		return messages.isEmpty();
	}

	/** {@code text} at most {@code max} characters long, ending in "…" when it was cut. */
	public static String cut(String text, int max) {
		if (text.length() <= max) {
			return text;
		}
		return text.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
	}

	/**
	 * Who sent a chat line shown as plain text, and what they said: the name just before the first ": ", "» " or "> "
	 * (ranks in brackets and angle brackets around it are fine) when {@code isPlayer} knows it; else null.
	 */
	public static String @Nullable [] sender(String line, Predicate<String> isPlayer) {
		int cut = -1;
		int skip = 0;
		for (String separator : new String[] {": ", "» ", "> "}) {
			int at = line.indexOf(separator);
			if (at > 0 && (cut < 0 || at < cut)) {
				cut = at;
				skip = separator.length();
			}
		}
		if (cut < 0) {
			return null;
		}
		String head = line.substring(0, cut + (line.charAt(cut) == '>' ? 1 : 0)).strip();
		String said = line.substring(cut + skip).strip();
		if (said.isEmpty()) {
			return null;
		}
		int space = head.lastIndexOf(' ');
		String name = head.substring(space + 1);
		if (name.startsWith("<") && name.endsWith(">")) {
			name = name.substring(1, name.length() - 1);
		} else if (name.endsWith(">")) {
			return null;
		}
		int bracket = name.lastIndexOf(']');
		if (bracket >= 0) {
			name = name.substring(bracket + 1);
		}
		return !name.isEmpty() && isPlayer.test(name) ? new String[] {name, said} : null;
	}
}
