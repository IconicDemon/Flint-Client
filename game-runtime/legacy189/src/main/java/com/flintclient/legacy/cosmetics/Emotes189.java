package com.flintclient.legacy.cosmetics;

import com.flintclient.client.cosmetics.CosmeticAnimation;
import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.DevCosmetics;
import com.flintclient.client.cosmetics.EmotePose;
import com.flintclient.client.cosmetics.ModelCosmetics;
import com.flintclient.client.cosmetics.ModelCosmetics.Loaded;
import com.flintclient.client.cosmetics.ModelItem;
import com.flintclient.client.module.social.SocialLink;
import com.flintclient.client.platform.Texture;
import com.flintclient.legacy.Flint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.client.options.KeyBinding;
import org.jspecify.annotations.Nullable;
import org.lwjgl.input.Keyboard;

/**
 * Your emotes on 1.8.9 (26.3's {@code Mc263Emotes}): one plays at a time on your own player ({@link EmotePose}, laid over the
 * model's pose by {@code mixin/cosmetics/PlayerModelEmoteMixin}), in third person (a first-person view switches to the back
 * view while it plays, and back after) and wherever your player is drawn (the inventory doll, the wardrobe's preview).
 * Moving, jumping or sneaking stops it, as does its end. The wheel ({@link EmoteWheelScreen189}, the "Emote Wheel" key, B,
 * under Flint Client in Controls) offers the 8 slots: what the Cosmetics window put there, else your emotes from the Flint app,
 * else the dev emotes. Others on the server see it through the emote room ({@link EmoteRoom189}). Game thread.
 */
public final class Emotes189 {
	public static final int SLOTS = 8;
	private static final long LOAD_WAIT_NANOS = 5_000_000_000L;
	private static final double MOVED = 0.05;

	/** Told {@code "start <id>"} / {@code "stop"} for your emotes (the emote room). */
	public static @Nullable Consumer<String> RELAY;

	private static @Nullable KeyBinding wheelKey;
	private static @Nullable Loaded<Texture> pending;
	private static long pendingSince;
	private static CosmeticAnimation.@Nullable Clip clip;
	private static @Nullable ModelItem playingItem;
	private static long startedAt;
	private static int restore = -1;
	private static double startX;
	private static double startY;
	private static double startZ;
	private static final ModelItem[] WHEEL = new ModelItem[SLOTS];
	private static boolean wheelSet;
	private static final long FADE_NANOS = (long) (EmotePose.BLEND_SECONDS * 1e9);
	private static CosmeticAnimation.@Nullable Clip fadeClip;
	private static double fadeSeconds;
	private static long fadeStart;
	private static float fadeWeight;

	private Emotes189() {
	}

	/** The "Emote Wheel" key (B), made on first use: Minecraft may read its options before Fabric starts Flint. */
	public static synchronized KeyBinding key() {
		if (wheelKey == null) {
			wheelKey = new KeyBinding("Emote Wheel", Keyboard.KEY_B, Flint.KEY_CATEGORY);
		}
		return wheelKey;
	}

	public static CosmeticAnimation.@Nullable Clip playing() {
		if (clip != null) {
			return clip;
		}
		return fadeClip != null && System.nanoTime() - fadeStart < FADE_NANOS ? fadeClip : null;
	}

	public static double seconds() {
		return clip != null ? (System.nanoTime() - startedAt) / 1e9 : fadeSeconds;
	}

	static float weight() {
		if (clip != null) {
			return EmotePose.weight(clip, seconds());
		}
		float f = 1 - (System.nanoTime() - fadeStart) / (float) FADE_NANOS;
		return Math.max(0, Math.min(fadeWeight, f * fadeWeight));
	}

	public static void wheel(List<@Nullable ModelItem> slots) {
		for (int i = 0; i < SLOTS; i++) {
			WHEEL[i] = i < slots.size() ? slots.get(i) : null;
		}
		wheelSet = true;
	}

	// ---------- the wheel's slots from the Flint app (your inventory's emotes) ----------

	private static volatile SocialLink.@Nullable Cosmetics fromLink;
	private static long askedAt = Long.MIN_VALUE / 4;
	private static volatile boolean asking;
	private static final Map<String, String> NAMES = new ConcurrentHashMap<>();

	static void refreshWheel() {
		long now = System.nanoTime();
		if (asking || now - askedAt < 30_000_000_000L) {
			return;
		}
		SocialLink link = SocialLink.get();
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

	static String name(ModelItem item) {
		String n = NAMES.get(item.id());
		return n != null ? n : item.id().startsWith("dev-") ? item.id().substring(4) : item.id();
	}

	private static ModelItem @Nullable [] linkSlots() {
		SocialLink.Cosmetics c = fromLink;
		ModelCosmetics<Texture> s = Cosmetics189.service();
		if (c == null || c.emotes() == null || s == null) {
			return null;
		}
		ModelItem[] out = new ModelItem[SLOTS];
		boolean any = false;
		for (int i = 0; i < SLOTS && i < c.emotes().size(); i++) {
			String id = c.emotes().get(i);
			for (SocialLink.Item item : c.items()) {
				if (id != null && id.equals(item.id())) {
					out[i] = Wardrobe189.RENDERER.item(item, s);
					if (out[i] != null) {
						NAMES.put(out[i].id(), item.name());
					}
					any |= out[i] != null;
				}
			}
		}
		return any ? out : null;
	}

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
		for (DevCosmetics.DevItem d : Cosmetics189.devItems()) {
			if (d.item().kind() == CosmeticKind.EMOTE) {
				dev.add(d.item());
			}
		}
		for (int i = 0; i < SLOTS && i < dev.size(); i++) {
			out[i] = dev.get(i);
		}
		return out;
	}

	/** Starts {@code emote} on you (once loaded). False when it can't be. */
	public static boolean play(ModelItem emote) {
		ModelCosmetics<Texture> s = Cosmetics189.service();
		if (s == null || emote.kind() != CosmeticKind.EMOTE) {
			return false;
		}
		stop(false);
		pending = s.request(emote);
		pendingSince = System.nanoTime();
		tryStart();
		return true;
	}

	/** Dev tours: plays wheel slot {@code slot} (0..7). */
	public static String playSlot(String slot) {
		int n = Integer.parseInt(slot.trim());
		ModelItem[] s = slots();
		ModelItem item = n >= 0 && n < SLOTS ? s[n] : null;
		return item == null ? "slot " + n + " is empty" : play(item) ? "playing " + item.id() : "can't play " + item.id();
	}

	/** Dev tours: what plays now. */
	public static String describe() {
		return clip == null ? (pending == null ? "no emote" : "loading " + pending.item.id())
			: "emote " + clip.name() + " at " + String.format(java.util.Locale.ROOT, "%.2f", seconds()) + " s";
	}

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
		if (restore >= 0 && mc.options.perspective == 1) {
			mc.options.perspective = restore;
		}
		restore = -1;
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
			Flint.LOG.warn("Flint emotes: {} can't be played: {}", l.item.id(), l.reason() == null ? "it didn't load in time" : l.reason());
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
		Minecraft mc = Minecraft.getInstance();
		LocalClientPlayerEntity player = mc.player;
		if (c == null) {
			return;
		}
		clip = c;
		playingItem = l.item;
		startedAt = System.nanoTime();
		if (player != null) {
			startX = player.x;
			startY = player.y;
			startZ = player.z;
			if (mc.options.perspective == 0) {
				restore = 0;
				mc.options.perspective = 1;
			}
		}
		Consumer<String> relay = RELAY;
		if (relay != null && player != null) {
			relay.accept("start " + l.item.id());
		}
	}

	/** Every tick: a pending emote starts once loaded; moving (or its end) stops the one playing; the wheel key. */
	static void tick(Minecraft mc, ModelCosmetics<Texture> s) {
		tryStart();
		LocalClientPlayerEntity player = mc.player;
		if (clip != null) {
			if (player == null) {
				// The wardrobe's preview on the title screen: it plays there until its end.
				if (clip.done(seconds())) {
					stop(false);
				}
			} else {
				if (playingItem != null) {
					s.request(playingItem);
				}
				double dx = player.x - startX;
				double dy = player.y - startY;
				double dz = player.z - startZ;
				boolean moving = player.input != null && (player.input.movementForward != 0 || player.input.movementSideways != 0
					|| player.input.jumping || player.input.sneaking) || dx * dx + dy * dy + dz * dz > MOVED * MOVED * 16 || player.vehicle != null;
				if (moving || clip.done(seconds())) {
					stop();
				}
			}
		}
		KeyBinding key = wheelKey;
		if (key == null) {
			return;
		}
		if (player != null) {
			refreshWheel();
		}
		while (key.consumeClick()) {
			refreshWheel();
			if (mc.screen == null && player != null) {
				mc.openScreen(new EmoteWheelScreen189(key));
			}
		}
	}

	// ---------- other players' emotes (the emote room) ----------

	private record Remote(Loaded<Texture> loaded, long startedAt) {
	}

	private static final Map<UUID, Remote> REMOTE = new java.util.HashMap<>();

	public static boolean remote(UUID player, ModelItem emote, long agoMillis) {
		ModelCosmetics<Texture> s = Cosmetics189.service();
		if (s == null || emote.kind() != CosmeticKind.EMOTE || REMOTE.size() > 200) {
			return false;
		}
		REMOTE.put(player, new Remote(s.request(emote), System.nanoTime() - Math.max(0, agoMillis) * 1_000_000L));
		return true;
	}

	public static void remoteStop(UUID player) {
		REMOTE.remove(player);
	}

	static CosmeticAnimation.@Nullable Clip remoteClip(UUID player, double[] seconds) {
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

	/** Dev tours: plays wheel slot {@code slot} on the player named {@code name} as if the room had announced it. */
	public static String remoteSlot(String name, String slot) {
		Minecraft mc = Minecraft.getInstance();
		int n = Integer.parseInt(slot.trim());
		ModelItem item = n >= 0 && n < SLOTS ? slots()[n] : null;
		if (mc.world == null || item == null) {
			return "no emote in slot " + n;
		}
		net.minecraft.entity.living.player.PlayerEntity p = mc.world.getPlayer(name);
		return p != null && remote(p.getUuid(), item, 0) ? "remote emote " + item.id() + " on " + name : "no player " + name;
	}
}
