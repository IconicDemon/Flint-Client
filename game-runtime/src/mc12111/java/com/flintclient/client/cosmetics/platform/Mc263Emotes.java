package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.cosmetics.CosmeticAnimation;
import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.DevCosmetics;
import com.flintclient.client.cosmetics.EmotePose;
import com.flintclient.client.cosmetics.ModelCosmetics;
import com.flintclient.client.cosmetics.ModelCosmetics.Loaded;
import com.flintclient.client.cosmetics.ModelItem;
import com.flintclient.client.platform.Texture;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Your emotes on 26.3: one plays at a time on your own player ({@link EmotePose}, laid over the model's pose by
 * {@code mixin/cosmetics/PlayerModelEmoteMixin}), in third person (a first-person view switches to the back view while it
 * plays, and back after) and wherever your player is drawn (the Cosmetics window's doll too). Moving, jumping or sneaking
 * stops it, as does its end (a clip that doesn't loop). The wheel ({@link EmoteWheelScreen}, the "Emote wheel" key) offers
 * the 8 slots: what the Cosmetics window put there ({@link #wheel}), else the dev emotes ({@code -Dflint.cosmetics.dev}).
 *
 * <p>Others don't see your emote yet: that needs the Friends live hub to relay "playing emote X" (contract phase 2).
 * {@link #RELAY} is the hook: it hears every emote you start and stop, and {@link #remote} is where an announced emote of
 * another player would be played.
 */
public final class Mc263Emotes {
	public static final int SLOTS = 8;
	/** How long an emote waits to load before it is dropped. */
	private static final long LOAD_WAIT_NANOS = 5_000_000_000L;
	private static final double MOVED = 0.05;

	/** Phase 2: told {@code "start <id>"} / {@code "stop"} for your emotes (null: nobody listens yet). */
	public static @Nullable Consumer<String> RELAY;

	private static @Nullable Loaded<Texture> pending;
	private static long pendingSince;
	private static CosmeticAnimation.@Nullable Clip clip;
	private static @Nullable ModelItem playingItem;
	private static long startedAt;
	private static @Nullable CameraType restore;
	private static @Nullable Vec3 startPos;
	private static final ModelItem[] WHEEL = new ModelItem[SLOTS];
	private static boolean wheelSet;

	private Mc263Emotes() {
	}

	/** The emote playing now, or fading out after it stopped (null: none). */
	public static CosmeticAnimation.@Nullable Clip playing() {
		if (clip != null) {
			return clip;
		}
		return fadeClip != null && System.nanoTime() - fadeStart < FADE_NANOS ? fadeClip : null;
	}

	/** Seconds into it (held where it stopped while it fades out). */
	public static double seconds() {
		return clip != null ? (System.nanoTime() - startedAt) / 1e9 : fadeSeconds;
	}

	/** How far it is blended in: up over its first 150 ms, down over its last, and down over 150 ms after it is stopped. */
	static float weight() {
		if (clip != null) {
			return EmotePose.weight(clip, seconds());
		}
		float f = 1 - (System.nanoTime() - fadeStart) / (float) FADE_NANOS;
		return Math.max(0, Math.min(fadeWeight, f * fadeWeight));
	}

	private static final long FADE_NANOS = (long) (EmotePose.BLEND_SECONDS * 1e9);
	private static CosmeticAnimation.@Nullable Clip fadeClip;
	private static double fadeSeconds;
	private static long fadeStart;
	private static float fadeWeight;

	/** The wheel's 8 slots (null: empty), as the Cosmetics window sets them from your equipped emotes. */
	public static void wheel(List<@Nullable ModelItem> slots) {
		for (int i = 0; i < SLOTS; i++) {
			WHEEL[i] = i < slots.size() ? slots.get(i) : null;
		}
		wheelSet = true;
	}

	// ---------- the wheel's slots from the Flint app (your inventory's emotes) ----------

	private static volatile com.flintclient.client.module.social.SocialLink.@Nullable Cosmetics fromLink;
	private static long askedAt = Long.MIN_VALUE / 4;
	private static volatile boolean asking;

	/** Asks the Flint app for your emotes (at most every 30 s; off the game's thread). The wheel shows them once in. */
	static void refreshWheel() {
		long now = System.nanoTime();
		if (asking || now - askedAt < 30_000_000_000L) {
			return;
		}
		com.flintclient.client.module.social.SocialLink link = com.flintclient.client.module.social.SocialLink.get();
		String who = com.flintclient.client.control.rshift.FlintFriendsScreen.playing();
		if (link == null || who == null) {
			return;
		}
		askedAt = now;
		asking = true;
		Thread t = new Thread(() -> {
			try {
				fromLink = link.cosmetics(who);
			} catch (Exception | LinkageError ex) {
				// The wheel keeps what it had.
			} finally {
				asking = false;
			}
		}, "Flint emote wheel");
		t.setDaemon(true);
		t.start();
	}

	private static final java.util.Map<String, String> NAMES = new java.util.concurrent.ConcurrentHashMap<>();

	/** An emote's name for the wheel (its Store name, else its id). */
	static String name(ModelItem item) {
		String n = NAMES.get(item.id());
		return n != null ? n : item.id().startsWith("dev-") ? item.id().substring(4) : item.id();
	}

	/** Your emotes on the wheel from the app's answer (null: none known yet). */
	private static ModelItem @Nullable [] linkSlots() {
		com.flintclient.client.module.social.SocialLink.Cosmetics c = fromLink;
		ModelCosmetics<Texture> s = Mc263Cosmetics.service();
		if (c == null || c.emotes() == null || s == null) {
			return null;
		}
		ModelItem[] out = new ModelItem[SLOTS];
		boolean any = false;
		for (int i = 0; i < SLOTS && i < c.emotes().size(); i++) {
			String id = c.emotes().get(i);
			for (com.flintclient.client.module.social.SocialLink.Item item : c.items()) {
				if (id != null && id.equals(item.id())) {
					out[i] = Mc263Cosmetics.WARDROBE.item(item, s);
					if (out[i] != null) {
						NAMES.put(out[i].id(), item.name());
					}
					any |= out[i] != null;
				}
			}
		}
		return any ? out : null;
	}

	/** The wheel's slots now: what was set through the API, else your emotes from the Flint app, else the dev emotes. */
	static ModelItem[] slots() {
		ModelItem[] out = new ModelItem[SLOTS];
		if (wheelSet) {
			System.arraycopy(WHEEL, 0, out, 0, SLOTS);
			return out;
		}
		ModelItem[] linked = linkSlots();
		if (linked != null) {
			return linked;
		}
		List<ModelItem> dev = new ArrayList<>();
		for (DevCosmetics.DevItem d : Mc263Cosmetics.devItems()) {
			if (d.item().kind() == CosmeticKind.EMOTE) {
				dev.add(d.item());
			}
		}
		for (int i = 0; i < SLOTS && i < dev.size(); i++) {
			out[i] = dev.get(i);
		}
		return out;
	}

	/** Starts {@code emote} on you (once loaded). False when it can't be. Game thread. */
	public static boolean play(ModelItem emote) {
		ModelCosmetics<Texture> s = Mc263Cosmetics.service();
		LocalPlayer player = Minecraft.getInstance().player;
		if (s == null || player == null || emote.kind() != CosmeticKind.EMOTE) {
			return false;
		}
		// A new emote replaces the one playing for others too: no stop goes out (were the new one refused, they keep the old).
		stop(false);
		pending = s.request(emote);
		pendingSince = System.nanoTime();
		tryStart();
		return true;
	}

	/** Dev tours: plays wheel slot {@code slot} (0..7). */
	public static String playSlot(int slot) {
		ModelItem[] s = slots();
		ModelItem item = slot >= 0 && slot < SLOTS ? s[slot] : null;
		return item == null ? "slot " + slot + " is empty" : play(item) ? "playing " + item.id() : "can't play " + item.id();
	}

	/** Dev tours: what plays now. */
	public static String describe() {
		return clip == null ? (pending == null ? "no emote" : "loading " + pending.item.id()) : "emote " + clip.name() + " at " + String.format(java.util.Locale.ROOT, "%.2f", seconds()) + " s";
	}

	/** Stops the emote playing (if any). */
	public static void stop() {
		stop(true);
	}

	private static void stop(boolean tellOthers) {
		pending = null;
		if (clip == null) {
			return;
		}
		fadeClip = clip;
		fadeSeconds = seconds();
		fadeWeight = EmotePose.weight(clip, fadeSeconds);
		fadeStart = System.nanoTime();
		clip = null;
		playingItem = null;
		Minecraft mc = Minecraft.getInstance();
		if (restore != null && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
			mc.options.setCameraType(restore);
		}
		restore = null;
		Consumer<String> relay = RELAY;
		if (relay != null && tellOthers) {
			relay.accept("stop");
		}
	}

	private static void tryStart() {
		Loaded<Texture> l = pending;
		if (l == null) {
			return;
		}
		if (l.state() == ModelCosmetics.FAILED || System.nanoTime() - pendingSince > LOAD_WAIT_NANOS) {
			FlintClient.LOGGER.warn("Flint emotes: {} can't be played: {}", l.item.id(), l.reason() == null ? "it didn't load in time" : l.reason());
			pending = null;
			return;
		}
		if (!l.drawable() || l.animation == null) {
			return;
		}
		pending = null;
		CosmeticAnimation.Clip c = null;
		for (CosmeticAnimation.Clip candidate : l.animation.clips().values()) {
			if (EmotePose.playable(candidate)) {
				c = candidate;
				break;
			}
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (c == null || player == null) {
			return;
		}
		clip = c;
		playingItem = l.item;
		startedAt = System.nanoTime();
		startPos = player.position();
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.getCameraType() == CameraType.FIRST_PERSON) {
			restore = CameraType.FIRST_PERSON;
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
		Consumer<String> relay = RELAY;
		if (relay != null) {
			relay.accept("start " + l.item.id());
		}
	}

	/** Every tick: a pending emote starts once loaded; moving (or its end) stops the one playing. */
	static void tick(ModelCosmetics<Texture> s) {
		tryStart();
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (clip == null) {
			EmoteKeys.tick(mc);
			return;
		}
		if (player == null) {
			stop();
			return;
		}
		if (playingItem != null) {
			// Kept loaded while it plays.
			s.request(playingItem);
		}
		Input keys = player.input.keyPresses;
		boolean moving = keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump() || keys.shift()
			|| startPos != null && player.position().distanceToSqr(startPos) > MOVED * MOVED * 16 || player.isFallFlying() || player.isPassenger();
		if (moving || clip.done(seconds())) {
			stop();
		}
		EmoteKeys.tick(mc);
	}

	// ---------- other players' emotes (phase 2: the relay isn't there yet) ----------

	/** Another player's emote: loading or playing since {@code startedAt} (System.nanoTime). */
	private record Remote(Loaded<Texture> loaded, long startedAt) {
	}

	private static final java.util.Map<java.util.UUID, Remote> REMOTE = new java.util.HashMap<>();

	/**
	 * Phase 2's entry point: another player near you started {@code emote} (a Store emote item, {@code asset.model} with its
	 * animation, as the relay would hand it over), {@code agoMillis} ago. It plays on them as yours plays on you, until it ends,
	 * {@link #remoteStop} or 30 s. Game thread. What the relay needs: the Friends live hub carrying "player P started emote E
	 * at T" (and "stopped") to Flint players in the same server, and the emote's item (its {@code asset.model}) from the Store's
	 * public lookup; the game calls this and {@link #remoteStop}, and announces your own through {@link #RELAY}.
	 */
	public static boolean remote(java.util.UUID player, ModelItem emote, long agoMillis) {
		ModelCosmetics<Texture> s = Mc263Cosmetics.service();
		if (s == null || emote.kind() != CosmeticKind.EMOTE || REMOTE.size() > 200) {
			return false;
		}
		REMOTE.put(player, new Remote(s.request(emote), System.nanoTime() - Math.max(0, agoMillis) * 1_000_000L));
		return true;
	}

	public static void remoteStop(java.util.UUID player) {
		REMOTE.remove(player);
	}

	/** Another player's emote clip now (null: none), for {@link Mc263Cosmetics#extract}; {@code seconds[0]}: how far into it. */
	static CosmeticAnimation.@Nullable Clip remoteClip(java.util.UUID player, double[] seconds) {
		Remote r = REMOTE.get(player);
		if (r == null || !r.loaded().drawable() || r.loaded().animation == null) {
			return null;
		}
		double t = (System.nanoTime() - r.startedAt()) / 1e9;
		for (CosmeticAnimation.Clip c : r.loaded().animation.clips().values()) {
			if (EmotePose.playable(c)) {
				if (c.done(t) || t > 30) {
					REMOTE.remove(player);
					return null;
				}
				seconds[0] = t;
				return c;
			}
		}
		return null;
	}

	/** Dev tours: plays wheel slot {@code slot} on the bot {@code player} as if the relay had announced it. */
	public static String remoteSlot(java.util.UUID player, int slot) {
		ModelItem item = slot >= 0 && slot < SLOTS ? slots()[slot] : null;
		return item != null && remote(player, item, 0) ? "remote emote " + item.id() + " on " + player : "no emote in slot " + slot;
	}
}
