package com.flintclient.client.module.screenshots;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The thumbnails a gallery screen shows, by screenshot index, and the decodes it is still waiting for.
 * A decode that stops being wanted (another page, another size, the screen closed) is cancelled: the
 * decoder skips it if it hasn't started ({@link Ticket#wanted()}), and a result that arrives anyway is
 * refused, so the caller frees it instead of showing it. Render thread, except {@link Ticket#wanted()}.
 *
 * @param <P> what a decoded thumbnail becomes (for the screen, its texture and sizes)
 */
public final class Thumbnails<P> {
	/** What a decode is for: screenshot {@code index}, cut to a {@code width}×{@code height} box ({@code cover}) or fitted inside it. */
	public record Want(int index, int width, int height, boolean cover) {
	}

	/** One requested decode. */
	public static final class Ticket {
		private volatile boolean cancelled;

		/** Read by the decoder just before it starts. */
		public boolean wanted() {
			return !cancelled;
		}
	}

	private record Shown<P>(Want want, P picture) {
	}

	private final Int2ObjectOpenHashMap<Shown<P>> shown = new Int2ObjectOpenHashMap<>();
	private final Map<Want, Ticket> pending = new HashMap<>();

	/** The picture shown for a screenshot, or null. Asked for every tile every frame, so it allocates nothing. */
	public @Nullable P picture(int index) {
		Shown<P> entry = shown.get(index);
		return entry == null ? null : entry.picture();
	}

	/**
	 * Keeps what screenshots [{@code from}, {@code to}) at this size need: other pictures are passed to
	 * {@code release} and forgotten, decodes for anything else are cancelled, and each missing picture
	 * that isn't already on the way is passed to {@code load} with a new ticket.
	 */
	public void keepOnly(int from, int to, int width, int height, boolean cover, Consumer<P> release, BiConsumer<Want, Ticket> load) {
		shown.values().removeIf(entry -> {
			if (matches(entry.want(), from, to, width, height, cover)) {
				return false;
			}
			release.accept(entry.picture());
			return true;
		});
		pending.entrySet().removeIf(entry -> {
			if (matches(entry.getKey(), from, to, width, height, cover)) {
				return false;
			}
			entry.getValue().cancelled = true;
			return true;
		});
		for (int i = from; i < to; i++) {
			Want want = new Want(i, width, height, cover);
			if (!shown.containsKey(i) && !pending.containsKey(want)) {
				Ticket ticket = new Ticket();
				pending.put(want, ticket);
				load.accept(want, ticket);
			}
		}
	}

	/**
	 * A decode finished ({@code picture} is null when it failed or was skipped). True when it is still
	 * wanted, and the picture is shown from now on; false means it was cancelled and the caller frees it.
	 */
	public boolean finish(Want want, Ticket ticket, @Nullable P picture) {
		if (ticket.cancelled || pending.get(want) != ticket) {
			return false;
		}
		pending.remove(want);
		if (picture != null) {
			shown.put(want.index(), new Shown<>(want, picture));
		}
		return true;
	}

	/** The screen closed: cancels every decode and passes every picture to {@code release}. */
	public void clear(Consumer<P> release) {
		for (Ticket ticket : pending.values()) {
			ticket.cancelled = true;
		}
		pending.clear();
		for (Shown<P> entry : shown.values()) {
			release.accept(entry.picture());
		}
		shown.clear();
	}

	/** Decodes still on the way. */
	public int loading() {
		return pending.size();
	}

	private static boolean matches(Want want, int from, int to, int width, int height, boolean cover) {
		return want.index() >= from && want.index() < to && want.width() == width && want.height() == height && want.cover() == cover;
	}
}
