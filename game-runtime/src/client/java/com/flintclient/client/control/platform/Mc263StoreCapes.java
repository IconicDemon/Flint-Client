package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.CapeFrames;
import com.flintclient.client.control.feature.StoreCapes;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.store.StoreCosmeticsClient;
import com.flintclient.client.control.store.StoreEndpoint;
import com.flintclient.client.control.store.StoreHandoff;
import com.flintclient.client.platform.Texture;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's side of {@link StoreCapes}: once a second, the players who may be drawn (you, then the players in the world by
 * distance) and the "Flint Store capes" option; every tick, the textures that finished loading are uploaded; and the cape in
 * a player's skin ({@code mixin/feature/PlayerInfoMixin}, so the cape layer, the elytra and Wavy Capes all draw it). Animated
 * capes are advanced once a frame ({@link #frame}, from the frame-time hook {@code mixin/DebugScreenOverlayMixin}: after the
 * frame was presented, where no render pass is open), writing a frame only when the wall clock moved to another, and only
 * for capes drawn in the last second (a player counts as drawn while their skin is asked for). One 64×32-per-unit texture per
 * animation (the poster's own when the frames are its size), so the cape layer, the elytra and Wavy Capes (UVs normalized to
 * 64×32) draw it unchanged.
 *
 * <p>Precedence: a cape the player equipped in the Flint Store takes the place of their Minecraft cape (equipping is their own
 * choice, made in the launcher; unequipping brings Minecraft's back at the next refresh). Nothing else changes: the wearer's
 * own "Cape" skin-customization switch still hides every cape, Nick Hider's hidden skins stay default (no Store cape either),
 * an elytra texture of Minecraft's stays, and with the option off only Minecraft's capes are drawn. A player Flint doesn't
 * know as wearing one keeps exactly the skin Minecraft gave.
 *
 * <p>Elytras follow Minecraft's rule (an elytra texture of the skin's, else the cape while it is shown, else the elytra's own):
 * a Store cape with elytra art is on the wings as a Minecraft cape is. One without (the Store's animated capes, and capes
 * drawn without wings) would make them an empty texture, so the wings get what Minecraft would have given them instead: the
 * wearer's Minecraft cape, or the elytra's own texture ({@link #wingsCape}, from {@code mixin/feature/WingsLayerMixin}).
 *
 * <p>The Store is the launcher's ({@link StoreHandoff}: {@code .flint-store.json} in the game directory, looked at once a second
 * with one {@code stat} and read only when it changed); when its {@code equipRevision} changes (the player equipped or removed
 * something in the launcher), your own cosmetics are asked for again at once, past any cache. Fault-isolated: a failure turns Store capes
 * off for the session, never the game.
 */
public final class Mc263StoreCapes {
	private static final HookGuard GUARD = new HookGuard("Flint Store capes",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	/** Players are gathered every this many ticks; textures are uploaded every tick (at most {@link #UPLOADS} a tick). */
	private static final int EVERY = 20;
	private static final int UPLOADS = 2;
	private static final int MAX_PLAYERS = 200;
	private static final List<UUID> WANTED = new ArrayList<>();
	private static final List<AbstractClientPlayer> NEAR = new ArrayList<>();
	private static final Map<UUID, Skinned> SKINS = new HashMap<>();
	private static @Nullable StoreCapes<Texture> capes;
	private static int ticks;
	private static @Nullable StoreHandoff handoff;
	private static long equipRevision;

	private Mc263StoreCapes() {
	}

	/**
	 * A player's skin with a Store cape, kept until either changes (so drawing allocates nothing); {@code wings}: the cape has
	 * elytra art ({@link StoreCapes#wings}).
	 */
	private record Skinned(PlayerSkin vanilla, Texture texture, PlayerSkin skin, boolean wings) {
	}

	/** The engine's textures for {@link StoreCapes}: Flint's own, registered with Minecraft's texture manager. */
	private static final StoreCapes.Textures<Texture> TEXTURES = new StoreCapes.Textures<>() {
		@Override
		public Texture upload(String name, int width, int height, int[] argb) {
			return Texture.upload(name, width, height, argb, false);
		}

		@Override
		public void write(Texture texture, int[] pixels, int width, int height) {
			texture.writeTopLeft(pixels, width, height);
		}

		@Override
		public void release(Texture texture) {
			texture.release();
		}

		@Override
		public CapeFrames.Layout frameLayout() {
			return CapeFrames.Layout.RGBA_BYTES;
		}
	};

	private static StoreCapes<Texture> service() {
		StoreCapes<Texture> service = capes;
		if (service == null) {
			StoreHandoff h = handoff;
			if (h == null) {
				h = new StoreHandoff(FabricLoader.getInstance().getGameDir());
				h.poll();
				equipRevision = h.equipRevision();
				handoff = h;
			}
			StoreEndpoint endpoint = h.endpoint();
			Path cache = Minecraft.getInstance().gameDirectory.toPath().resolve("flint").resolve("cache").resolve("cosmetics");
			service = new StoreCapes<>(new StoreCosmeticsClient(endpoint, StoreCosmeticsClient.http()), cache, TEXTURES,
				StoreCapes.loaderThread());
			FlintClient.LOGGER.info("Flint Store capes: using {}", endpoint);
			capes = service;
		}
		return service;
	}

	/** Every client tick. */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			StoreCapes<Texture> service = service();
			service.upload(UPLOADS);
			if (++ticks % EVERY != 0) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			User user = mc.getUser();
			UUID self = user == null ? null : user.getProfileId();
			WANTED.clear();
			LocalPlayer player = mc.player;
			ClientLevel level = mc.level;
			if (player != null) {
				self = player.getUUID();
			}
			if (self != null) {
				// Yours first, even before a world (the title screen fetches it ahead).
				WANTED.add(self);
			}
			if (player != null && level != null) {
				NEAR.clear();
				for (AbstractClientPlayer other : level.players()) {
					if (other != player) {
						NEAR.add(other);
					}
				}
				NEAR.sort(Comparator.comparingDouble(other -> other.distanceToSqr(player)));
				for (int i = 0; i < NEAR.size() && WANTED.size() < MAX_PLAYERS; i++) {
					WANTED.add(NEAR.get(i).getUUID());
				}
				NEAR.clear();
			}
			service = handoff(service, self);
			service.update(System.nanoTime(), ControlState.settings().gameBool(GameOptions.STORE_CAPES), WANTED);
			SKINS.keySet().removeIf(id -> capes == null || !capes.wears(id));
		} catch (RuntimeException | LinkageError e) {
			failed(e);
		}
	}

	/** Store capes are off for the session: every texture goes, and skins are Minecraft's again. */
	private static void failed(Throwable e) {
		GUARD.fail(e);
		SKINS.clear();
		StoreCapes<Texture> service = capes;
		if (service != null) {
			try {
				service.clear();
			} catch (RuntimeException | LinkageError ignored) {
				// Off for the session either way.
			}
		}
	}

	/**
	 * Every rendered frame, after it was presented (render thread, no render pass open): brings the animated capes drawn in the
	 * last second to the wall clock's frame. A clock read when nothing plays.
	 */
	public static void frame() {
		StoreCapes<Texture> service = capes;
		if (service == null || !GUARD.on()) {
			return;
		}
		try {
			service.animate(System.currentTimeMillis());
		} catch (RuntimeException | LinkageError e) {
			failed(e);
		}
	}

	/**
	 * The launcher rewrote its handoff: a different Store starts over; a new {@code equipRevision} (compared for inequality: it goes
	 * back to 0 when the launcher restarts) asks for your own cosmetics again now instead of at the next refresh. The file only
	 * says when to ask: what you wear is the Store's answer.
	 */
	private static StoreCapes<Texture> handoff(StoreCapes<Texture> service, @Nullable UUID self) {
		StoreHandoff h = handoff;
		if (h == null || !h.poll()) {
			return service;
		}
		long revision = h.equipRevision();
		boolean equipped = revision != equipRevision;
		equipRevision = revision;
		StoreEndpoint endpoint = h.endpoint();
		if (!endpoint.equals(service.client().endpoint())) {
			service.clear();
			SKINS.clear();
			capes = null;
			return service();
		}
		if (equipped && self != null) {
			service.refresh(self);
		}
		return service;
	}

	/** {@code info}'s skin with the cape they equipped in the Flint Store, if they wear one; otherwise {@code skin} as it is. */
	public static PlayerSkin skin(PlayerInfo info, PlayerSkin skin) {
		StoreCapes<Texture> service = capes;
		if (service == null || !GUARD.on()) {
			return skin;
		}
		try {
			UUID id = info.getProfile().id();
			Texture texture = id == null ? null : service.texture(id);
			if (texture == null) {
				return skin;
			}
			if (!Minecraft.getInstance().isSameThread()) {
				return withCape(skin, texture.id());
			}
			Skinned s = SKINS.get(id);
			if (s == null || s.vanilla() != skin || s.texture() != texture) {
				s = new Skinned(skin, texture, withCape(skin, texture.id()), service.wings(id));
				SKINS.put(id, s);
			}
			return s.skin();
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return skin;
		}
	}

	/**
	 * The cape an elytra takes its texture from when {@code skin} has no elytra texture of its own (Minecraft's
	 * {@code WingsLayer}, which then draws {@code cape} while the wearer shows their cape): {@code cape} itself, unless it is a Store
	 * cape without elytra art, whose wings would be empty. Then the cape Minecraft gave the wearer (their Minecraft cape, or none:
	 * the elytra's own texture), exactly as without Flint. Render thread.
	 */
	public static ClientAsset.@Nullable Texture wingsCape(PlayerSkin skin, ClientAsset.@Nullable Texture cape) {
		if (cape == null || capes == null || !GUARD.on()) {
			return cape;
		}
		try {
			for (Skinned s : SKINS.values()) {
				if (s.skin() == skin) {
					return s.wings() ? cape : s.vanilla().cape();
				}
			}
			return cape;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return cape;
		}
	}

	/** {@code skin} with {@code cape} as its cape; body, model, elytra and the secure flag as they were. */
	private static PlayerSkin withCape(PlayerSkin skin, Identifier cape) {
		return new PlayerSkin(skin.body(), new ClientAsset.ResourceTexture(cape, cape), skin.elytra(), skin.model(), skin.secure());
	}

	/** One line for the dev tour: what the Store capes know and show. */
	public static String describe() {
		StoreCapes<Texture> service = capes;
		return service == null ? "Store capes: not started" : service.describe();
	}
}
