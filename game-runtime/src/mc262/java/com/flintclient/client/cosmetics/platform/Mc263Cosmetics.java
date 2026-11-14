package com.flintclient.client.cosmetics.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.StoreCapes;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.platform.HookGuard;
import com.flintclient.client.cosmetics.CosmeticKind;
import com.flintclient.client.cosmetics.CosmeticPreview;
import com.flintclient.client.cosmetics.DevCosmetics;
import com.flintclient.client.cosmetics.ModelCosmetics;
import com.flintclient.client.cosmetics.ModelCosmetics.Loaded;
import com.flintclient.client.cosmetics.ModelItem;
import com.flintclient.client.cosmetics.ModelPose;
import com.flintclient.client.platform.Texture;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.2's side of the 3D cosmetics ({@link ModelCosmetics}; 26.3's code): the service is made next to Store capes' (it hears the
 * same lookups, {@link StoreCapes#listen}); every tick the models that finished loading are uploaded and, once a second, the
 * players who may be drawn are handed over; when a player's render state is extracted ({@link #extract}) it gets their
 * {@link Worn}, which {@link CosmeticsLayer} draws. "Flint Store capes" (Settings → Cosmetics) shows or hides them, as it
 * does capes. Fault-isolated: a failure turns the 3D cosmetics off for the session, never the game.
 *
 * <p>Performance: a model is baked once and drawn through Minecraft's batched entity buffers; its pose is worked out at most
 * once a frame, only for players within {@link #ANIMATE_DISTANCE} blocks and at most {@link #MAX_ANIMATED} instances a
 * frame (others keep the pose they last had); nothing is drawn past {@link #DRAW_DISTANCE} blocks.
 */
public final class Mc263Cosmetics {
	static final HookGuard GUARD = new HookGuard("Flint 3D cosmetics",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	static final Identifier MISSING = Identifier.withDefaultNamespace("missingno");
	/** Players drawn with their cosmetics up to this far (blocks). */
	static final double DRAW_DISTANCE = 64;
	/** Models are animated up to this far; beyond, they hold their last pose. */
	static final double ANIMATE_DISTANCE = 32;
	/** At most this many models are posed in one frame (the nearest first come first). */
	static final int MAX_ANIMATED = 24;
	private static final int EVERY = 20;
	private static final int MAX_PLAYERS = 200;

	private static @Nullable ModelCosmetics<Texture> service;
	private static @Nullable StoreCapes<?> boundTo;
	private static final Map<UUID, Worn> WORN = new HashMap<>();
	private static final List<UUID> IN_VIEW = new ArrayList<>();
	private static final List<AbstractClientPlayer> NEAR = new ArrayList<>();
	private static List<DevCosmetics.DevItem> dev = List.of();
	private static boolean devRead;
	private static ModelItem @Nullable [] preview;
	private static int ticks;
	private static final double[] REMOTE_SECONDS = new double[1];
	private static int posedThisFrame;
	private static long frames;

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

	private Mc263Cosmetics() {
	}

	/** The service, made (or made again) for the Store capes service in use; null until that exists. */
	static @Nullable ModelCosmetics<Texture> service() {
		StoreCapes<?> capes = StoreCapes.shared();
		if (capes == null) {
			return null;
		}
		ModelCosmetics<Texture> s = service;
		if (s == null || boundTo != capes) {
			if (s != null) {
				s.clear();
			}
			Path cache = Minecraft.getInstance().gameDirectory.toPath().resolve("flint").resolve("cache").resolve("cosmetics3d");
			s = new ModelCosmetics<>(capes.client(), cache, TEXTURES, ModelCosmetics.loaderThread());
			s.allowLocal(DevCosmetics.root());
			if (capes.cache() != null) {
				// Flint's shared cache of verified files (the launcher's and every profile's), Flint's CDN first: a model file is
				// downloaded once per computer. Dev items (file: addresses) keep this class's own reading.
				StoreCapes<?> files = capes;
				s.useFetcher((file, maxBytes) -> files.file(file.cdn(), file.url(), file.sha256(), maxBytes, "3D file"));
			}
			// Items the Flint app describes (the wardrobe, the emote wheel, other players' emotes in the room): their files come
			// through the game link, checked by the app.
			s.useLinkFiles((id, part) -> {
				com.flintclient.client.module.social.SocialLink link = com.flintclient.client.module.social.SocialLink.get();
				String who = com.flintclient.client.control.rshift.FlintFriendsScreen.playing();
				if (link == null || who == null) {
					throw new IllegalStateException("no Flint app");
				}
				return link.modelFile(who, id, part);
			});
			// Your emotes go to the server's emote room, so the Flint players there see them.
			Mc263Emotes.RELAY = Mc263EmoteRoom::relay;
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
			com.flintclient.client.control.feature.CosmeticPreview.register(WARDROBE);
		}
		return s;
	}

	/** Every client tick. */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			ModelCosmetics<Texture> s = service();
			if (s == null) {
				return;
			}
			s.upload(2);
			Mc263Emotes.tick(s);
			Mc263EmoteRoom.tick(Minecraft.getInstance(), s);
			if (++ticks % EVERY != 0) {
				return;
			}
			gather();
			boolean on = ControlState.settings().gameBool(GameOptions.STORE_CAPES);
			wearDev(s);
			s.update(System.nanoTime(), on, IN_VIEW);
			long now = System.nanoTime();
			WORN.values().removeIf(w -> now - w.lastSeen > 10_000_000_000L);
		} catch (RuntimeException | LinkageError e) {
			failed(e);
		}
	}

	/** You, then the players in the world by distance. */
	private static void gather() {
		IN_VIEW.clear();
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		UUID self = player != null ? player.getUUID() : mc.getUser() == null ? null : mc.getUser().getProfileId();
		if (self != null) {
			IN_VIEW.add(self);
		}
		if (player != null && level != null) {
			NEAR.clear();
			for (AbstractClientPlayer other : level.players()) {
				if (other != player) {
					NEAR.add(other);
				}
			}
			NEAR.sort(Comparator.comparingDouble(other -> other.distanceToSqr(player)));
			for (int i = 0; i < NEAR.size() && IN_VIEW.size() < MAX_PLAYERS; i++) {
				IN_VIEW.add(NEAR.get(i).getUUID());
			}
			NEAR.clear();
		}
	}

	/** Dev items ({@code -Dflint.cosmetics.dev}) and the Cosmetics window's preview, on you (and dev items for all, on everyone). */
	private static void wearDev(ModelCosmetics<Texture> s) {
		if (!devRead) {
			devRead = true;
			Path root = DevCosmetics.root();
			if (root != null) {
				dev = DevCosmetics.read(root);
				FlintClient.LOGGER.info("Flint 3D cosmetics: {} dev items from {}", dev.size(), root);
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

	/**
	 * Dev tours: the dev items of subfolder {@code set} of the {@code -Dflint.cosmetics.dev} folder instead ("" for the folder
	 * itself). Answers what was read.
	 */
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
		StringBuilder names = new StringBuilder();
		for (DevCosmetics.DevItem d : dev) {
			names.append(names.length() == 0 ? "" : ", ").append(d.item().kind().id).append(' ').append(d.folder()).append(d.everyone() ? " (all)" : "");
		}
		return dev.size() + " dev items from " + set + ": " + names;
	}

	static List<DevCosmetics.DevItem> devItems() {
		return dev;
	}

	/** After each frame was presented: a new frame's animation budget. */
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
	 * When a player's render state is extracted (world, inventory doll, a preview): what they wear and do this frame, on the
	 * state ({@link CosmeticsState}). Render thread.
	 */
	public static void extract(Avatar entity, AvatarRenderState state) {
		CosmeticsState cs = (CosmeticsState) state;
		cs.flint$worn(null);
		ModelCosmetics<Texture> s = service;
		if (s == null || !GUARD.on() || !(entity instanceof Player player)) {
			return;
		}
		try {
			UUID id = player.getUUID();
			Loaded<Texture>[] loaded = s.worn(id);
			boolean self = player == Minecraft.getInstance().player;
			com.flintclient.client.cosmetics.CosmeticAnimation.Clip remote = self ? null : Mc263Emotes.remoteClip(id, REMOTE_SECONDS);
			boolean emoting = self ? Mc263Emotes.playing() != null : remote != null;
			if (loaded == null && !emoting) {
				return;
			}
			Worn w = WORN.get(id);
			if (w == null) {
				w = new Worn(id);
				WORN.put(id, w);
			}
			w.lastSeen = System.nanoTime();
			boolean hidesCape = false;
			for (int k = 0; k < w.instances.length; k++) {
				Loaded<Texture> l = loaded == null ? null : loaded[k];
				Worn.Instance inst = w.instances[k];
				if (l == null || !l.drawable()) {
					w.instances[k] = null;
				} else if (inst == null || inst.loaded != l) {
					w.instances[k] = new Worn.Instance(l);
				}
				if (l != null && l.item.hidesCape()) {
					hidesCape = true;
				}
			}
			w.hidesCape = hidesCape;
			w.motion = state.isFallFlying || player.getAbilities().flying ? ModelPose.FLY
				: state.isVisuallySwimming || state.isInWater && state.swimAmount > 0 ? ModelPose.SWIM
				: state.walkAnimationSpeed > 0.15F ? ModelPose.WALK : ModelPose.IDLE;
			Minecraft mc = Minecraft.getInstance();
			w.ownView = self && mc.options.getCameraType() == CameraType.FIRST_PERSON && dolls == 0;
			w.emote = !emoting ? null : self ? Mc263Emotes.playing() : remote;
			w.emoteSeconds = !emoting ? 0 : self ? Mc263Emotes.seconds() : REMOTE_SECONDS[0];
			w.emoteWeight = !emoting ? 0 : self ? Mc263Emotes.weight() : com.flintclient.client.cosmetics.EmotePose.weight(remote, REMOTE_SECONDS[0]);
			cs.flint$worn(w);
		} catch (RuntimeException | LinkageError e) {
			failed(e);
		}
	}

	/**
	 * Vertices other players' cosmetics may take in full detail in one frame; past it they are drawn at the simplest detail
	 * (their biggest faces only, unanimated), and past twice it not at all. Yours are always full.
	 */
	static final int MAX_VERTICES = 25_000;
	private static int verticesThisFrame;

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

	/** Your id (null before a world). */
	static @Nullable UUID selfId() {
		LocalPlayer p = Minecraft.getInstance().player;
		return p == null ? null : p.getUUID();
	}

	/** Whether a model may be posed now (the frame's budget), counting it. */
	static boolean mayPose() {
		return posedThisFrame++ < MAX_ANIMATED;
	}

	/** Time spent in the 3D cosmetics (posing and submitting in the layer, writing vertices), and frames, since {@link #perf}. */
	static long submitNanos;
	static long drawNanos;
	static long vertices;
	private static long perfFrames;

	/** Dev tours: the 3D cosmetics' own cost per frame since the last call (render thread time), then starts again. */
	public static String perf() {
		long f = Math.max(1, perfFrames);
		String out = String.format(java.util.Locale.ROOT, "3D cosmetics cost: %.1f us/frame posing+submitting, %.1f us/frame writing %d vertices/frame (%d frames)",
			submitNanos / 1000.0 / f, drawNanos / 1000.0 / f, vertices / f, perfFrames);
		submitNanos = 0;
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

	// ---------- the Cosmetics window's preview (CosmeticPreview) ----------

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
				s.wearExtra(mc.player.getUUID(), null);
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
			return m != null && Mc263Emotes.play(m);
		}

		@Override
		public void stopEmote() {
			Mc263Emotes.stop();
		}

		@Override
		public void wheel(List<@Nullable JsonObject> slots) {
			ModelCosmetics<Texture> s = service;
			List<@Nullable ModelItem> items = new ArrayList<>();
			for (int i = 0; i < Mc263Emotes.SLOTS; i++) {
				JsonObject o = i < slots.size() ? slots.get(i) : null;
				items.add(o == null || s == null ? null : ModelItem.read(o, CosmeticKind.EMOTE, s.endpoint()));
			}
			Mc263Emotes.wheel(items);
		}
	};

	// ---------- the wardrobe's preview player (control/feature/CosmeticPreview) ----------

	static final Mc263WardrobeRenderer WARDROBE = new Mc263WardrobeRenderer();
	/** What the wardrobe's preview player wears (it has no entity: one Worn of its own). */
	private static final Worn PREVIEW_WORN = new Worn(new UUID(0, 0x464C494E54L));

	/**
	 * A player render state made for a screen (no entity extracted it): while the wardrobe draws its preview player, it wears
	 * the wardrobe's selection, and your emote plays on it. Render thread.
	 */
	public static void preview(AvatarRenderState state) {
		ModelCosmetics<Texture> s = service;
		CosmeticsState cs = (CosmeticsState) state;
		if (s == null || !GUARD.on() || cs.flint$worn() != null || !com.flintclient.client.control.feature.CosmeticPreview.drawingPreview()) {
			return;
		}
		try {
			java.util.Map<String, com.flintclient.client.module.social.SocialLink.Item> wearing = com.flintclient.client.control.feature.CosmeticPreview.wearing();
			Worn w = PREVIEW_WORN;
			boolean hides = false;
			for (int k = 0; k < w.instances.length; k++) {
				com.flintclient.client.module.social.SocialLink.Item item = wearing.get(CosmeticKind.WORN[k].slot);
				ModelItem m = item == null ? null : WARDROBE.item(item, s);
				Loaded<Texture> l = m == null || m.kind() != CosmeticKind.WORN[k] ? null : s.request(m);
				Worn.Instance inst = w.instances[k];
				if (l == null || !l.drawable()) {
					w.instances[k] = null;
				} else if (inst == null || inst.loaded != l) {
					w.instances[k] = new Worn.Instance(l);
				}
				hides |= l != null && l.item.hidesCape();
			}
			w.hidesCape = hides;
			w.motion = ModelPose.IDLE;
			w.ownView = false;
			w.emote = Mc263Emotes.playing();
			w.emoteSeconds = w.emote == null ? 0 : Mc263Emotes.seconds();
			w.emoteWeight = w.emote == null ? 0 : Mc263Emotes.weight();
			w.lastSeen = System.nanoTime();
			cs.flint$worn(w);
		} catch (RuntimeException | LinkageError e) {
			failed(e);
		}
	}

	private static int dolls;

	/** A player is being extracted for a doll in a screen (the inventory, the Cosmetics window): what's on your head shows. */
	public static void dollBegin() {
		dolls++;
	}

	public static void dollEnd() {
		dolls = Math.max(0, dolls - 1);
	}

	/** Whether the cape of the player {@code state} draws is hidden by the wings they wear. */
	public static boolean hidesCape(AvatarRenderState state) {
		Worn w = ((CosmeticsState) state).flint$worn();
		return w != null && w.hidesCape && GUARD.on();
	}

	/** Your own worn cosmetics (first person's shield), or null. */
	static @Nullable Worn self() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player == null ? null : WORN.get(player.getUUID());
	}
}
