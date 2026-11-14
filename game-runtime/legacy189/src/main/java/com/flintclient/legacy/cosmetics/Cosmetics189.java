package com.flintclient.legacy.cosmetics;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.StoreCapes;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.platform.HookGuard;
import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.CosmeticPreview;
import com.flintclient.client.cosmetics.DevCosmetics;
import com.flintclient.client.cosmetics.EmotePose;
import com.flintclient.client.cosmetics.ModelCosmetics;
import com.flintclient.client.cosmetics.ModelCosmetics.Loaded;
import com.flintclient.client.cosmetics.ModelItem;
import com.flintclient.client.cosmetics.ModelPose;
import com.flintclient.client.module.social.SocialLink;
import com.flintclient.client.platform.Texture;
import com.flintclient.legacy.Flint;
import com.flintclient.shaded.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.entity.living.player.PlayerEntity;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 1.8.9's side of the 3D cosmetics (26.3's {@code Mc263Cosmetics}, the same shared service {@link ModelCosmetics}):
 * made next to Store capes' (it hears the same lookups); every tick the models that finished loading are uploaded and, once
 * a second, the players who may be drawn are handed over; when a player is drawn, {@link #worn} brings their {@link Worn189}
 * up to date once a frame, which the player renderer's layer ({@link CosmeticsLayer189}) draws and the emote pose
 * ({@code mixin/cosmetics/PlayerModelEmoteMixin}) reads. "Flint Store capes" shows or hides them, as it does capes. The same
 * limits: drawn up to 64 blocks, animated up to 32, at most 24 models posed a frame, a crowd's budget of vertices.
 * Fault-isolated: a failure turns the 3D cosmetics off for the session, never the game. Render thread.
 */
public final class Cosmetics189 {
	static final HookGuard GUARD = new HookGuard("Flint 3D cosmetics",
		(path, error) -> Flint.LOG.warn("Flint: the {} hook failed; it's off for this session", path, error));
	static final double DRAW_DISTANCE = 64;
	static final double ANIMATE_DISTANCE = 32;
	static final int MAX_ANIMATED = 24;
	static final int MAX_VERTICES = 25_000;
	private static final int EVERY = 20;
	private static final int MAX_PLAYERS = 200;

	private static @Nullable ModelCosmetics<Texture> service;
	private static @Nullable StoreCapes<?> boundTo;
	private static final Map<UUID, Worn189> WORN = new HashMap<>();
	private static final List<UUID> IN_VIEW = new ArrayList<>();
	private static final List<PlayerEntity> NEAR = new ArrayList<>();
	private static List<DevCosmetics.DevItem> dev = List.of();
	private static boolean devRead;
	private static ModelItem @Nullable [] preview;
	private static int ticks;
	private static final double[] REMOTE_SECONDS = new double[1];
	private static int posedThisFrame;
	private static int verticesThisFrame;
	static long frames;
	static long drawNanos;
	static long vertices;
	private static long perfFrames;
	private static int dolls;

	private static final ModelCosmetics.Textures<Texture> TEXTURES = new ModelCosmetics.Textures<>() {
		@Override
		public Texture upload(String name, int width, int height, int[] argb) {
			return Texture.upload(name, width, height, argb, false);
		}

		@Override
		public void release(Texture texture) {
			texture.release();
		}
	};

	private Cosmetics189() {
	}

	/** The service, made (or made again) for the Store capes service in use; null until that exists. */
	public static @Nullable ModelCosmetics<Texture> service() {
		StoreCapes<?> capes = StoreCapes.shared();
		if (capes == null) {
			return null;
		}
		ModelCosmetics<Texture> s = service;
		if (s == null || boundTo != capes) {
			if (s != null) {
				s.clear();
			}
			Path cache = Minecraft.getInstance().gameDir.toPath().resolve("flint").resolve("cache").resolve("cosmetics3d");
			s = new ModelCosmetics<>(capes.client(), cache, TEXTURES, ModelCosmetics.loaderThread());
			s.allowLocal(DevCosmetics.root());
			if (capes.cache() != null) {
				StoreCapes<?> files = capes;
				s.useFetcher((file, maxBytes) -> files.file(file.cdn(), file.url(), file.sha256(), maxBytes, "3D file"));
			}
			s.useLinkFiles((id, part) -> {
				SocialLink link = SocialLink.get();
				String who = com.flintclient.client.control.rshift.FlintFriendsScreen.playing();
				if (link == null || who == null) {
					throw new IllegalStateException("no Flint app");
				}
				return link.modelFile(who, id, part);
			});
			Emotes189.RELAY = EmoteRoom189::relay;
			ModelCosmetics<Texture> bound = s;
			capes.listen((asked, lookup) -> {
				if (GUARD.on()) {
					try {
						bound.answered(asked, lookup);
					} catch (RuntimeException | LinkageError e) {
						failed(e);
					}
				}
			});
			service = s;
			boundTo = capes;
			WORN.clear();
			CosmeticPreview.install(PREVIEW);
			com.flintclient.client.control.feature.CosmeticPreview.register(Wardrobe189.RENDERER);
		}
		return s;
	}

	/** Every client tick. */
	public static void tick(Minecraft mc) {
		if (!GUARD.on()) {
			return;
		}
		try {
			ModelCosmetics<Texture> s = service();
			if (s == null) {
				return;
			}
			s.upload(2);
			Emotes189.tick(mc, s);
			EmoteRoom189.tick(mc, s);
			if (++ticks % EVERY != 0) {
				return;
			}
			gather(mc);
			boolean on = ControlState.settings().gameBool(GameOptions.STORE_CAPES);
			wearDev(s);
			s.update(System.nanoTime(), on, IN_VIEW);
			long now = System.nanoTime();
			WORN.values().removeIf(w -> now - w.lastSeen > 10_000_000_000L);
		} catch (RuntimeException | LinkageError e) {
			failed(e);
		}
	}

	private static void gather(Minecraft mc) {
		IN_VIEW.clear();
		ClientPlayerEntity player = mc.player;
		UUID self = player != null ? player.getUuid()
			: mc.getSession() == null || mc.getSession().getProfile() == null ? null : mc.getSession().getProfile().getId();
		if (self != null) {
			IN_VIEW.add(self);
		}
		if (player != null && mc.world != null) {
			NEAR.clear();
			for (Object o : mc.world.players) {
				PlayerEntity other = (PlayerEntity) o;
				if (other != player) {
					NEAR.add(other);
				}
			}
			NEAR.sort(Comparator.comparingDouble(other -> other.squaredDistanceTo(player)));
			for (int i = 0; i < NEAR.size() && IN_VIEW.size() < MAX_PLAYERS; i++) {
				IN_VIEW.add(NEAR.get(i).getUuid());
			}
			NEAR.clear();
		}
	}

	private static void wearDev(ModelCosmetics<Texture> s) {
		if (!devRead) {
			devRead = true;
			Path root = DevCosmetics.root();
			if (root != null) {
				dev = DevCosmetics.read(root);
				Flint.LOG.info("Flint 3D cosmetics: {} dev items from {}", dev.size(), root);
			}
		}
		if (dev.isEmpty() && preview == null) {
			return;
		}
		for (int i = 0; i < IN_VIEW.size(); i++) {
			UUID id = IN_VIEW.get(i);
			List<ModelItem> items = new ArrayList<>();
			if (i == 0 && preview != null) {
				for (ModelItem item : preview) {
					items.add(item);
				}
			}
			for (DevCosmetics.DevItem d : dev) {
				if (d.item().kind() != CosmeticKind.EMOTE && (i == 0 || d.everyone())) {
					items.add(d.item());
				}
			}
			s.wearExtra(id, items.isEmpty() ? null : items.toArray(new ModelItem[0]));
		}
	}

	/** Dev tours: the dev items of subfolder {@code set} of the dev folder instead. */
	public static String devSet(String set) {
		Path root = DevCosmetics.root();
		ModelCosmetics<Texture> s = service();
		if (root == null || s == null) {
			return "no dev folder (-D" + DevCosmetics.PROPERTY + ")";
		}
		Path dir = root.resolve(set).normalize();
		if (!dir.startsWith(root)) {
			return "outside the dev folder";
		}
		devRead = true;
		dev = DevCosmetics.read(dir);
		for (UUID id : IN_VIEW) {
			s.wearExtra(id, null);
		}
		ticks = EVERY - 1;
		return dev.size() + " dev items from " + set;
	}

	static List<DevCosmetics.DevItem> devItems() {
		return dev;
	}

	/** After each frame was presented: a new frame's budgets. */
	public static void frame() {
		posedThisFrame = 0;
		verticesThisFrame = 0;
		frames++;
		perfFrames++;
	}

	static void failed(Throwable e) {
		GUARD.fail(e);
		try {
			com.flintclient.client.control.feature.CosmeticPreview.register(null);
		} catch (RuntimeException | LinkageError ignored) {
			// Off either way.
		}
		WORN.clear();
		ModelCosmetics<Texture> s = service;
		if (s != null) {
			try {
				s.clear();
			} catch (RuntimeException | LinkageError ignored) {
				// Off for the session either way.
			}
		}
	}

	/**
	 * What {@code player} wears and does this frame (brought up to date once a frame), or null: nothing to draw or pose. The
	 * wardrobe's preview player (no entity: {@code player} null) wears the wardrobe's selection.
	 */
	static @Nullable Worn189 worn(@Nullable PlayerEntity player) {
		ModelCosmetics<Texture> s = service;
		if (s == null || !GUARD.on()) {
			return null;
		}
		try {
			if (player == null) {
				return com.flintclient.client.control.feature.CosmeticPreview.drawingPreview() ? Wardrobe189.previewWorn(s) : null;
			}
			UUID id = player.getUuid();
			Worn189 w = WORN.get(id);
			if (w != null && w.frame == frames) {
				return w.instancesEmpty() && w.emote == null ? null : w;
			}
			Loaded<Texture>[] loaded = s.worn(id);
			boolean self = player == Minecraft.getInstance().player;
			com.flintclient.client.cosmetics.CosmeticAnimation.Clip remote = self ? null : Emotes189.remoteClip(id, REMOTE_SECONDS);
			boolean emoting = self ? Emotes189.playing() != null : remote != null;
			if (loaded == null && !emoting) {
				if (w != null) {
					w.frame = frames;
					w.clear();
				}
				return null;
			}
			if (w == null) {
				w = new Worn189(id);
				WORN.put(id, w);
			}
			w.frame = frames;
			w.lastSeen = System.nanoTime();
			boolean hidesCape = false;
			for (int k = 0; k < w.instances.length; k++) {
				Loaded<Texture> l = loaded == null ? null : loaded[k];
				Worn189.Instance inst = w.instances[k];
				if (l == null || !l.drawable()) {
					w.instances[k] = null;
				} else if (inst == null || inst.loaded != l) {
					w.instances[k] = new Worn189.Instance(l);
				}
				if (l != null && l.item.hidesCape()) {
					hidesCape = true;
				}
			}
			w.hidesCape = hidesCape;
			w.emote = !emoting ? null : self ? Emotes189.playing() : remote;
			w.emoteSeconds = !emoting ? 0 : self ? Emotes189.seconds() : REMOTE_SECONDS[0];
			w.emoteWeight = !emoting ? 0 : self ? Emotes189.weight() : EmotePose.weight(remote, REMOTE_SECONDS[0]);
			return w;
		} catch (RuntimeException | LinkageError e) {
			failed(e);
			return null;
		}
	}

	/** How a model of {@code vertices} fits the frame's budget (counting it): 0 full, 1 simplest detail, 2 not drawn. */
	static int budget(int vertices) {
		if (verticesThisFrame + vertices <= MAX_VERTICES) {
			verticesThisFrame += vertices;
			return 0;
		}
		int simple = vertices / 4;
		if (verticesThisFrame + simple <= 2 * MAX_VERTICES) {
			verticesThisFrame += simple;
			return 1;
		}
		return 2;
	}

	static boolean mayPose() {
		return posedThisFrame++ < MAX_ANIMATED;
	}

	static @Nullable UUID selfId() {
		ClientPlayerEntity p = Minecraft.getInstance().player;
		return p == null ? null : p.getUuid();
	}

	/** A doll in a screen (the inventory) is being drawn. */
	public static void dollBegin() {
		dolls++;
	}

	public static void dollEnd() {
		dolls = Math.max(0, dolls - 1);
	}

	static boolean inDoll() {
		return dolls > 0;
	}

	/** Whether {@code player}'s cape is hidden by the wings they wear. */
	public static boolean hidesCape(PlayerEntity player) {
		Worn189 w = worn(player);
		return w != null && w.hidesCape;
	}

	/** Dev tours: the 3D cosmetics' own cost per frame since the last call. */
	public static String perf() {
		long f = Math.max(1, perfFrames);
		String out = String.format(java.util.Locale.ROOT, "3D cosmetics cost: %.1f us/frame posing+drawing, %d vertices/frame (%d frames)",
			drawNanos / 1000.0 / f, vertices / f, perfFrames);
		drawNanos = 0;
		vertices = 0;
		perfFrames = 0;
		return out;
	}

	/** One line for the dev tour. */
	public static String describe() {
		ModelCosmetics<Texture> s = service;
		return s == null ? "3D cosmetics: not started" : s.describe() + ", " + WORN.size() + " drawn players, " + dev.size() + " dev items";
	}

	// ---------- the Cosmetics window's preview API (cosmetics/CosmeticPreview) ----------

	private static final CosmeticPreview.Backend PREVIEW = new CosmeticPreview.Backend() {
		@Override
		public String show(List<JsonObject> items) {
			ModelCosmetics<Texture> s = service;
			if (s == null) {
				return "not ready";
			}
			List<ModelItem> read = new ArrayList<>();
			for (JsonObject o : items) {
				ModelItem item = ModelItem.read(o, null, s.endpoint());
				if (item != null && item.kind() != CosmeticKind.EMOTE) {
					read.add(item);
					s.request(item);
				}
			}
			preview = read.isEmpty() ? null : read.toArray(new ModelItem[0]);
			ticks = EVERY - 1;
			return read.size() + " of " + items.size() + " shown";
		}

		@Override
		public void clear() {
			preview = null;
			ModelCosmetics<Texture> s = service;
			Minecraft mc = Minecraft.getInstance();
			if (s != null && mc.player != null && dev.isEmpty()) {
				s.wearExtra(mc.player.getUuid(), null);
			}
			ticks = EVERY - 1;
		}

		@Override
		public String status(JsonObject item) {
			ModelCosmetics<Texture> s = service;
			if (s == null) {
				return "unavailable";
			}
			ModelItem m = ModelItem.read(item, null, s.endpoint());
			if (m == null) {
				return "unsupported";
			}
			Loaded<Texture> l = s.request(m);
			return switch (l.state()) {
				case ModelCosmetics.UPLOADED -> "ready";
				case ModelCosmetics.FAILED -> "failed: " + l.reason();
				default -> "loading";
			};
		}

		@Override
		public boolean emote(JsonObject emote) {
			ModelCosmetics<Texture> s = service;
			ModelItem m = s == null ? null : ModelItem.read(emote, CosmeticKind.EMOTE, s.endpoint());
			return m != null && Emotes189.play(m);
		}

		@Override
		public void stopEmote() {
			Emotes189.stop();
		}

		@Override
		public void wheel(List<@Nullable JsonObject> slots) {
			ModelCosmetics<Texture> s = service;
			List<@Nullable ModelItem> items = new ArrayList<>();
			for (int i = 0; i < Emotes189.SLOTS; i++) {
				JsonObject o = i < slots.size() ? slots.get(i) : null;
				items.add(o == null || s == null ? null : ModelItem.read(o, CosmeticKind.EMOTE, s.endpoint()));
			}
			Emotes189.wheel(items);
		}
	};

	/** The motion clip a player plays from 1.8.9's own state (no swimming pose on 1.8.9). */
	static int motion(PlayerEntity player, float walkSpeed) {
		return player.abilities.flying ? ModelPose.FLY : walkSpeed > 0.15F ? ModelPose.WALK : ModelPose.IDLE;
	}
}
