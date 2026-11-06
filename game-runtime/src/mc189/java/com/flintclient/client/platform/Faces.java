package com.flintclient.client.platform;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Session;
import net.minecraft.client.network.PlayerInfo;
import net.minecraft.client.network.handler.ClientPlayNetworkHandler;
import net.minecraft.client.resource.skin.DefaultSkinUtils;
import net.minecraft.resource.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The players the menus show, on Minecraft 1.8.9: the signed-in player (the header: their name, id and face) and other
 * players by UUID (the Friends window), each as their skin's face with its hat layer. A player in the world the game is in is
 * drawn with the skin Minecraft already has for them; anyone else's profile is looked up on Mojang's session server (as 1.8.9
 * looks up a skull's owner) and their skin loaded through Minecraft's own skin cache. Until it has loaded (or offline) the
 * UUID's default skin is shown, as Minecraft does.
 *
 * <p>A lookup that fails is tried again after 10 seconds, then after doubling waits up to 5 minutes, while the face is still
 * shown (as {@code AccountSkin} does on the newer lines). Lookups run one at a time on a worker thread of their own. Platform
 * class (see {@link Clock}). Render thread, but for the worker.
 */
public final class Faces {
	private static final float UNIT = 1 / 64.0F;
	private static final long FIRST_RETRY_MS = 10_000L;
	private static final long LAST_RETRY_MS = 300_000L;
	/** A skin whose download started but never said it arrived is asked for again after this long. */
	private static final long LOAD_TIMEOUT_MS = 30_000L;
	private static final Map<UUID, Lookup> LOOKUPS = new HashMap<>();
	private static @Nullable ThreadPoolExecutor worker;

	private Faces() {
	}

	/** The signed-in player's name. */
	public static String ownName() {
		return Minecraft.getInstance().getSession().getUsername();
	}

	/** The signed-in player's UUID, 32 lowercase hex digits ("" when the session has none). */
	public static String ownId() {
		UUID id = ownUuid();
		return id == null ? "" : id.toString().replace("-", "");
	}

	private static @Nullable UUID ownUuid() {
		Session session = Minecraft.getInstance().getSession();
		try {
			return session == null || session.getProfile() == null ? null : session.getProfile().getId();
		} catch (IllegalArgumentException e) {
			// A session whose id isn't a UUID.
			return null;
		}
	}

	/** The signed-in player's face, {@code size} pixels square at ({@code x}, {@code y}) in the frame's pixel space. */
	public static void own(Frame frame, int x, int y, int size) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			face(frame, mc.player.getSkinTextureLocation(), x, y, size);
			return;
		}
		UUID id = ownUuid();
		face(frame, id == null ? DefaultSkinUtils.getDefaultSkin() : skin(mc, id), x, y, size);
	}

	/** Another player's face by UUID (either spelling); their default skin's until theirs has loaded. */
	public static void of(Frame frame, String uuid, int x, int y, int size) {
		UUID id = parse(uuid);
		face(frame, id == null ? DefaultSkinUtils.getDefaultSkin() : skin(Minecraft.getInstance(), id), x, y, size);
	}

	private static void face(Frame frame, Identifier skin, int x, int y, int size) {
		frame.blitGame(skin, x, y, x + size, y + size, 8 * UNIT, 8 * UNIT, 16 * UNIT, 16 * UNIT, 0xFFFFFFFF);
		frame.blitGame(skin, x, y, x + size, y + size, 40 * UNIT, 8 * UNIT, 48 * UNIT, 16 * UNIT, 0xFFFFFFFF);
	}

	/** {@code id}'s skin as it can be drawn now. */
	private static Identifier skin(Minecraft mc, UUID id) {
		ClientPlayNetworkHandler handler = mc.getNetworkHandler();
		PlayerInfo listed = handler == null ? null : handler.getOnlinePlayer(id);
		if (listed != null) {
			// In the player list: Minecraft has (or is loading) their skin already.
			return listed.getSkinTexture();
		}
		Lookup lookup = LOOKUPS.get(id);
		if (lookup == null) {
			lookup = new Lookup(id);
			LOOKUPS.put(id, lookup);
		}
		return lookup.skin(mc);
	}

	/** A UUID with or without its dashes, or null. */
	static @Nullable UUID parse(@Nullable String text) {
		if (text == null) {
			return null;
		}
		String hex = text.trim().replace("-", "");
		if (hex.length() != 32) {
			return null;
		}
		try {
			return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static synchronized ThreadPoolExecutor worker() {
		ThreadPoolExecutor w = worker;
		if (w == null) {
			w = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
				Thread thread = new Thread(task, "Flint faces");
				thread.setDaemon(true);
				return thread;
			});
			w.allowCoreThreadTimeOut(true);
			worker = w;
		}
		return w;
	}

	/** One player's skin: being asked for, loading, loaded, the default one (they wear none), or waiting to be asked again. */
	private static final class Lookup {
		private static final int IDLE = 0;
		private static final int ASKING = 1;
		private static final int LOADING = 2;
		private static final int DEFAULT = 3;
		private static final int FAILED = 4;

		private final UUID id;
		/** The skin once Minecraft has it (set when its download is in, on whichever thread finishes it). */
		private volatile @Nullable Identifier loaded;
		private volatile int state = IDLE;
		private long retryAt;
		private long wait = FIRST_RETRY_MS;
		private long loadingSince;

		Lookup(UUID id) {
			this.id = id;
		}

		Identifier skin(Minecraft mc) {
			Identifier skin = loaded;
			if (skin != null) {
				return skin;
			}
			long now = System.currentTimeMillis();
			int s = state;
			if (s == LOADING && now - loadingSince > LOAD_TIMEOUT_MS) {
				s = FAILED;
			}
			if (s == FAILED) {
				s = IDLE;
				state = IDLE;
				retryAt = now + wait;
				wait = Math.min(wait * 2, LAST_RETRY_MS);
			}
			if (s == IDLE && now >= retryAt) {
				ask(mc);
			}
			return DefaultSkinUtils.getDefaultSkin(id);
		}

		/** The profile from Mojang's session server, then (back on the render thread) its skin through Minecraft's skin cache. */
		private void ask(Minecraft mc) {
			state = ASKING;
			MinecraftSessionService sessions = mc.getSessionService();
			try {
				worker().execute(() -> {
					try {
						// Signed, which isn't cached: the unsigned lookup keeps a failed answer for six hours.
						GameProfile profile = sessions.fillProfileProperties(new GameProfile(id, null), true);
						if (profile == null || !profile.getProperties().containsKey("textures")) {
							state = FAILED;
							return;
						}
						MinecraftProfileTexture texture = sessions.getTextures(profile, false).get(MinecraftProfileTexture.Type.SKIN);
						if (texture == null) {
							state = DEFAULT;
							return;
						}
						mc.executeTask(() -> load(mc, texture));
					} catch (RuntimeException e) {
						state = FAILED;
					}
				});
			} catch (RuntimeException e) {
				state = FAILED;
			}
		}

		private void load(Minecraft mc, MinecraftProfileTexture texture) {
			try {
				loadingSince = System.currentTimeMillis();
				state = LOADING;
				mc.getSkinManager().register(texture, MinecraftProfileTexture.Type.SKIN, (type, location, profileTexture) -> loaded = location);
			} catch (RuntimeException e) {
				state = FAILED;
			}
		}
	}
}
