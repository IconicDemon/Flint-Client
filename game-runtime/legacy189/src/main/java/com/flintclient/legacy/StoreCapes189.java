package com.flintclient.legacy;

import com.flintclient.client.control.feature.CapeFrames;
import com.flintclient.client.control.feature.StoreCapes;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.store.StoreCosmeticsClient;
import com.flintclient.client.control.store.StoreEndpoint;
import com.flintclient.client.control.store.StoreHandoff;
import com.flintclient.legacy.shared.Shared;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Session;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.texture.DynamicTexture;
import net.minecraft.client.render.texture.TextureUtil;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.resource.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 1.8.9's side of the shared {@link StoreCapes}: once a second, you and the players in the world (nearest first) and
 * the "Flint Store capes" setting; every tick, finished textures uploaded as 1.8.9 dynamic textures; and the cape location a
 * player's cape layer binds ({@code mixin/ClientPlayerEntityMixin}). Same rules as the newer lines: an equipped Store cape
 * takes the place of the Minecraft cape, the wearer's own "Cape" skin part still hides it, and with the setting off only
 * Minecraft's capes are drawn. 1.8.9 has no elytra. A failure turns Store capes off for the session, never the game.
 *
 * <p>Animated capes: 1.8.9 may write a texture while it draws (no render passes), so a cape is brought to the wall clock's frame
 * just before its layer binds it ({@link #cape}): only capes being drawn are advanced, and only when the frame changed. The
 * frame is uploaded into the cape's rectangle of the same texture with {@code glTexSubImage2D} (1.8.9's TextureUtil, through
 * its one shared upload buffer: no allocation).
 */
public final class StoreCapes189 {
	private static final int EVERY = 20;
	private static final int UPLOADS = 2;
	private static final int MAX_PLAYERS = 200;
	private static final List<UUID> WANTED = new ArrayList<>();
	private static final List<PlayerEntity> NEAR = new ArrayList<>();
	private static @Nullable StoreCapes<Cape> capes;
	/** The one-frame mip chain TextureUtil.upload takes (its frame set just before each upload). */
	private static final int[][] FRAME = new int[1][];
	private static boolean failed;
	private static int ticks;
	private static @Nullable StoreHandoff handoff;
	private static long equipRevision;

	private StoreCapes189() {
	}

	/** A Store cape's texture: where the texture manager knows it, and the texture itself (for animated capes' frames). */
	private record Cape(Identifier id, DynamicTexture texture) {
	}

	/** 1.8.9's textures: a dynamic texture of the pixels, registered with the texture manager under {@code flintclient:<name>}. */
	private static final StoreCapes.Textures<Cape> TEXTURES = new StoreCapes.Textures<>() {
		@Override
		public Cape upload(String name, int width, int height, int[] argb) {
			DynamicTexture texture = new DynamicTexture(width, height);
			System.arraycopy(argb, 0, texture.getPixels(), 0, width * height);
			texture.upload();
			Identifier id = new Identifier("flintclient", name);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			return new Cape(id, texture);
		}

		@Override
		public void write(Cape cape, int[] pixels, int width, int height) {
			// Bound on the active unit, where the cape layer binds it next anyway; the same parameters the texture was made with.
			GlStateManager.bindTexture(cape.texture().getGlId());
			FRAME[0] = pixels;
			try {
				TextureUtil.upload(FRAME, width, height, 0, 0, false, false);
			} finally {
				FRAME[0] = null;
			}
		}

		@Override
		public void release(Cape cape) {
			Minecraft.getInstance().getTextureManager().close(cape.id());
		}

		@Override
		public CapeFrames.Layout frameLayout() {
			return CapeFrames.Layout.ARGB;
		}
	};

	private static StoreCapes<Cape> service(Minecraft mc) {
		StoreCapes<Cape> service = capes;
		if (service == null) {
			StoreHandoff h = handoff;
			if (h == null) {
				h = new StoreHandoff(FabricLoader.getInstance().getGameDir());
				h.poll();
				equipRevision = h.equipRevision();
				handoff = h;
			}
			StoreEndpoint endpoint = h.endpoint();
			Path cache = mc.gameDir.toPath().resolve("flint").resolve("cache").resolve("cosmetics");
			service = new StoreCapes<>(new StoreCosmeticsClient(endpoint, StoreCosmeticsClient.http()), cache, TEXTURES, StoreCapes.loaderThread());
			Flint.LOG.info("Flint Store capes: using " + endpoint);
			capes = service;
		}
		return service;
	}

	/** Every client tick. */
	static void tick(Minecraft mc) {
		if (failed) {
			return;
		}
		try {
			StoreCapes<Cape> service = service(mc);
			service.upload(UPLOADS);
			if (++ticks % EVERY != 0) {
				return;
			}
			Session session = mc.getSession();
			UUID self = session == null || session.getProfile() == null ? null : session.getProfile().getId();
			ClientPlayerEntity player = mc.player;
			if (player != null) {
				self = player.getUuid();
			}
			WANTED.clear();
			if (self != null) {
				WANTED.add(self);
			}
			if (player != null && mc.world != null) {
				NEAR.clear();
				for (PlayerEntity other : mc.world.players) {
					if (other != player) {
						NEAR.add(other);
					}
				}
				NEAR.sort(Comparator.comparingDouble(other -> other.squaredDistanceTo(player)));
				for (int i = 0; i < NEAR.size() && WANTED.size() < MAX_PLAYERS; i++) {
					WANTED.add(NEAR.get(i).getUuid());
				}
				NEAR.clear();
			}
			service = handoff(mc, service, self);
			service.update(System.nanoTime(), Shared.settings().gameBool(GameOptions.STORE_CAPES), WANTED);
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			Flint.LOG.warn("Flint: Store capes failed; they're off for this session", e);
			StoreCapes<Cape> service = capes;
			if (service != null) {
				try {
					service.clear();
				} catch (RuntimeException | LinkageError ignored) {
					// Off for the session either way.
				}
			}
		}
	}

	/**
	 * The launcher rewrote its handoff ({@code .flint-store.json}, {@link StoreHandoff}): another Store starts over; a new
	 * {@code equipRevision} asks for your own cosmetics again now, past any cache. What you wear is the Store's answer.
	 */
	private static StoreCapes<Cape> handoff(Minecraft mc, StoreCapes<Cape> service, @Nullable UUID self) {
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
			capes = null;
			return service(mc);
		}
		if (equipped && self != null) {
			service.refresh(self);
		}
		return service;
	}

	/** One line for QA tours: what the Store capes know and show. */
	public static String describe() {
		StoreCapes<Cape> service = capes;
		return failed ? "Store capes: off after a failure" : service == null ? "Store capes: not started" : service.describe();
	}

	/**
	 * The cape texture {@code player} equipped in the Flint Store, or null (none, not loaded, or Store capes off). On the game's
	 * thread (the cape layer about to bind it), an animated cape is first brought to the wall clock's frame.
	 */
	public static @Nullable Identifier cape(ClientPlayerEntity player) {
		StoreCapes<Cape> service = capes;
		if (service == null || failed) {
			return null;
		}
		UUID id = player.getUuid();
		if (id == null) {
			return null;
		}
		try {
			if (Minecraft.getInstance().isOnSameThread()) {
				service.animate(id, System.currentTimeMillis());
			}
			Cape cape = service.texture(id);
			return cape == null ? null : cape.id();
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			Flint.LOG.warn("Flint: Store capes failed; they're off for this session", e);
			try {
				service.clear();
			} catch (RuntimeException | LinkageError ignored) {
				// Off for the session either way.
			}
			return null;
		}
	}
}
