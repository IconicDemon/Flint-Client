package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.store.CapeAnimation;
import com.flintclient.client.control.store.StoreCosmeticsClient;
import com.flintclient.client.control.store.StoreCosmeticsClient.Response;
import com.flintclient.client.control.store.StoreEndpoint;
import com.google.gson.JsonParser;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Animated Store capes ({@link StoreCapes} with {@link CapeAnimation}s), on the Store's own e2e fixtures
 * ({@link AnimatedCapeFixture}): the poster first, then the strips, frames written into one texture per animation on the wall
 * clock, only when the frame changes and only while drawn; the poster whenever anything about the animation fails.
 */
class StoreCapesAnimatedTest {
	private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
	private static final UUID ALEX = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
	private static final UUID NOTCH = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae7");
	private static final String ASSET_ID = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff";
	private static final long T = 10_000_000_000_000L;
	/** A wall clock at the start of a loop of {@link #STANDARD}'s (1030 ms) and {@link #HD}'s (905 ms). */
	private static final long EPOCH = 1_762_000_000_000L / (1030L * 905) * (1030L * 905);
	private static final Cape STANDARD = Cape.of("standard", 16, AnimatedCapeFixture.STANDARD);
	private static final Cape HD = Cape.of("hd", 32, AnimatedCapeFixture.HD);

	private static long s(long seconds) {
		return TimeUnit.SECONDS.toNanos(seconds);
	}

	/** An animated cape as the Store serves it: its poster and strips, and the asset entry its lookup answers. */
	record Cape(String name, int scale, int[] durations, List<byte[]> strips, byte[] poster, String posterSha, int width, int height,
		String animation) {
		static Cape of(String name, int scale, int[] durations) {
			List<byte[]> strips = AnimatedCapeFixture.strips(scale, durations.length);
			int s = Math.min(scale, 16);
			byte[] poster = CapePng.encodeRgba(64 * s, 32 * s, AnimatedCapeFixture.poster(scale, durations.length));
			return new Cape(name, scale, durations, strips, poster, StoreCapes.sha256(poster), 64 * s, 32 * s,
				AnimatedCapeFixture.animationJson(scale, durations, strips, AnimatedCapeFixture.BUCKET));
		}

		String posterUrl() {
			return "https://store.flintclient.com/test-capes/" + name + ".png";
		}

		List<String> stripUrls() {
			List<String> urls = new ArrayList<>();
			for (byte[] strip : strips) {
				urls.add(AnimatedCapeFixture.BUCKET + "animated/" + StoreCapes.sha256(strip) + ".png");
			}
			return urls;
		}

		String asset(String animation) {
			return "{\"id\":\"" + ASSET_ID + "\",\"version\":1,\"path\":\"posters/" + posterSha + ".png\",\"sha256\":\"" + posterSha
				+ "\",\"width\":" + width + ",\"height\":" + height + ",\"url\":\"" + posterUrl() + "\",\"animation\":" + animation + "}";
		}

		CapeAnimation parsed() {
			return CapeAnimation.parse(JsonParser.parseString(animation), StoreEndpoint.defaults());
		}

		/** The texture the game makes for it: named by the poster's hash and the animation's key. */
		String texture() {
			return "store-capes/" + posterSha + "." + parsed().key();
		}

		/** Frame {@code f} as the game keeps it at {@code kept} px per unit (ARGB). */
		int[] frame(int f, int kept) {
			return AnimatedCapeFixture.frame(f, durations.length, kept);
		}
	}

	/** The Store and its bucket as the game sees them. */
	static final class Store implements StoreCosmeticsClient.Transport {
		final Map<UUID, String> wearing = new HashMap<>();
		final Map<String, byte[]> files = new HashMap<>();
		final List<URI> downloads = new ArrayList<>();
		int lookups;
		boolean stripsOffline;

		@Override
		public CompletableFuture<Response> get(URI uri, String accept, int maxBytes, boolean fresh) {
			if (uri.getPath().equals("/api/v1/players/cosmetics/")) {
				lookups++;
				StringJoiner players = new StringJoiner(",");
				for (String id : uri.getRawQuery().substring("uuids=".length()).split(",")) {
					String asset = wearing.get(UUID.fromString(id));
					if (asset != null) {
						players.add("\"" + id + "\":{\"cape\":{\"cosmetic_id\":\"11111111-2222-4333-8444-555555555555\",\"kind\":\"cape\",\"asset\":"
							+ asset + "}}");
					}
				}
				byte[] body = ("{\"players\":{" + players + "}}").getBytes(StandardCharsets.UTF_8);
				return CompletableFuture.completedFuture(new Response(200, body, "public, max-age=60", null));
			}
			downloads.add(uri);
			if (stripsOffline && uri.getPath().contains("/animated/")) {
				return CompletableFuture.failedFuture(new ConnectException("no route"));
			}
			byte[] file = files.get(uri.toString());
			if (file != null && file.length > maxBytes) {
				return CompletableFuture.failedFuture(new StoreCosmeticsClient.Refused("answer larger than " + maxBytes + " bytes"));
			}
			return CompletableFuture.completedFuture(file == null ? new Response(404, new byte[0], null, null) : new Response(200, file, null, null));
		}

		void serve(Cape cape) {
			files.put(cape.posterUrl(), cape.poster());
			for (int k = 0; k < cape.strips().size(); k++) {
				files.put(cape.stripUrls().get(k), cape.strips().get(k));
			}
		}

		void wear(UUID player, Cape cape) {
			serve(cape);
			wearing.put(player, cape.asset(cape.animation()));
		}

		long stripDownloads() {
			return downloads.stream().filter(u -> u.getPath().contains("/animated/")).count();
		}
	}

	/** Textures as names: what was uploaded (and at what size), written and released. */
	static final class Gpu implements StoreCapes.Textures<String> {
		final List<String> uploaded = new ArrayList<>();
		final Map<String, int[]> sizes = new HashMap<>();
		final List<String> released = new ArrayList<>();
		final List<String> writtenTo = new ArrayList<>();
		final List<int[]> written = new ArrayList<>();
		boolean failWrites;

		@Override
		public String upload(String name, int width, int height, int[] argb) {
			String texture = name + "#" + (uploaded.size() + 1);
			uploaded.add(texture);
			sizes.put(texture, new int[] {width, height});
			assertEquals(width * height, argb.length);
			return texture;
		}

		@Override
		public void write(String texture, int[] pixels, int width, int height) {
			if (failWrites) {
				throw new IllegalStateException("the device is lost");
			}
			assertTrue(uploaded.contains(texture) && !released.contains(texture), "only a live texture is written: " + texture);
			int[] size = sizes.get(texture);
			assertTrue(width <= size[0] && height <= size[1], "within the texture");
			assertEquals(width * height, pixels.length);
			writtenTo.add(texture);
			written.add(pixels);
		}

		@Override
		public void release(String texture) {
			released.add(texture);
		}
	}

	/** A game on a fake Store, stepped one second at a time. */
	static final class Game {
		final Store store;
		final Gpu gpu = new Gpu();
		final StoreCapes<String> capes;
		long now = T;
		List<UUID> players = List.of(STEVE);

		Game(Path cache) {
			this(cache, new Store(), Runnable::run, StoreCapes.MAX_FRAME_BYTES, CapeFrames.MAX_BYTES);
		}

		Game(Path cache, Store store, Executor loader, long maxFrameBytes, long maxCapeFrameBytes) {
			this.store = store;
			capes = new StoreCapes<>(new StoreCosmeticsClient(StoreEndpoint.defaults(), store), cache, gpu, loader, maxFrameBytes,
				maxCapeFrameBytes);
		}

		void run(long seconds) {
			for (long i = 0; i < seconds; i++) {
				capes.update(now, true, players);
				capes.upload(2);
				now += s(1);
			}
		}

		/** One rendered frame at {@code epochMillis}: the frame hook, then {@code drawn} players' capes are drawn. */
		void frame(long epochMillis, UUID... drawn) {
			capes.animate(epochMillis);
			for (UUID id : drawn) {
				capes.texture(id);
			}
		}
	}

	/** Updates (a second apart) until an animated cape plays: its poster is up after 4, its strips load in the 5th. */
	private static final int PLAYING = 6;

	private static Game wearing(Path cache, Cape cape) {
		Game game = new Game(cache);
		game.store.wear(STEVE, cape);
		return game;
	}

	@Test
	void theStandardFixturePlaysInItsPostersTexture(@TempDir Path cache) {
		Game game = wearing(cache, STANDARD);
		game.run(PLAYING);
		String texture = STANDARD.texture() + "#1";
		assertEquals(texture, game.capes.texture(STEVE), "the poster's texture");
		assertEquals(List.of(texture), game.gpu.uploaded, "one texture: the frames are the poster's size");
		assertEquals(2, game.store.stripDownloads());
		assertEquals(CapeFrames.bytes(10, 16), game.capes.framesInMemory());

		game.frame(EPOCH, STEVE);
		assertTrue(game.gpu.written.isEmpty(), "frame 0 is the poster: nothing to write");
		int[] durations = AnimatedCapeFixture.STANDARD;
		long t = EPOCH;
		for (int loop = 0; loop < 2; loop++) {
			for (int f = 0; f < durations.length; f++) {
				int writes = game.gpu.written.size();
				game.frame(t, STEVE);
				game.frame(t + durations[f] / 2, STEVE);
				game.frame(t + durations[f] - 1, STEVE);
				if (loop == 0 && f == 0) {
					assertEquals(0, game.gpu.written.size());
				} else {
					assertEquals(writes + 1, game.gpu.written.size(), "one write per frame, however many frames are drawn: loop " + loop
						+ " frame " + f);
					assertArrayEquals(STANDARD.frame(f, 16), game.gpu.written.get(writes), "frame " + f);
					assertEquals(texture, game.gpu.writtenTo.get(writes));
				}
				t += durations[f];
			}
		}
		assertEquals(List.of(texture), game.gpu.uploaded, "no new texture per frame");
	}

	@Test
	void aCapeNotDrawnForASecondIsntAdvanced(@TempDir Path cache) {
		Game game = wearing(cache, STANDARD);
		game.run(PLAYING);
		game.frame(EPOCH, STEVE);
		game.frame(EPOCH + 40, STEVE);
		assertEquals(1, game.gpu.written.size());
		// Steve walks behind you: his skin isn't asked for any more.
		game.frame(EPOCH + 120);
		assertEquals(2, game.gpu.written.size(), "drawn in the last second");
		game.frame(EPOCH + 1100);
		game.frame(EPOCH + 1500);
		game.frame(EPOCH + 3000);
		assertEquals(2, game.gpu.written.size(), "not drawn for a second: left alone");
		game.frame(EPOCH + 3010, STEVE);
		game.frame(EPOCH + 3020);
		assertEquals(3, game.gpu.written.size(), "drawn again: at the frame of the wall clock at once");
		assertArrayEquals(STANDARD.frame(STANDARD.parsed().frameAt(EPOCH + 3020), 16), game.gpu.written.get(2));
	}

	@Test
	void everyClientShowsTheSameFrameAtTheSameMoment(@TempDir Path first, @TempDir Path second) {
		Game mine = wearing(first, STANDARD);
		Game theirs = wearing(second, STANDARD);
		mine.run(PLAYING);
		theirs.run(PLAYING);
		long t = EPOCH + 123_457;
		for (int i = 0; i < 200; i++, t += 17) {
			mine.frame(t, STEVE);
			theirs.frame(t, STEVE);
		}
		assertFalse(mine.gpu.written.isEmpty());
		assertEquals(mine.gpu.written.size(), theirs.gpu.written.size());
		for (int i = 0; i < mine.gpu.written.size(); i++) {
			assertArrayEquals(mine.gpu.written.get(i), theirs.gpu.written.get(i));
		}
	}

	@Test
	void anHdCapeIsHalvedIntoItsPostersTexture(@TempDir Path cache) {
		Game game = wearing(cache, HD);
		game.run(PLAYING);
		String texture = HD.texture() + "#1";
		assertEquals(texture, game.capes.texture(STEVE));
		assertArrayEquals(new int[] {1024, 512}, game.gpu.sizes.get(texture), "the poster is 16 px per unit");
		assertEquals(3, game.store.stripDownloads(), "8 HD frames in strips of 3");
		assertEquals(CapeFrames.bytes(8, 16), game.capes.framesInMemory(), "kept at the poster's scale, not HD's");
		game.frame(EPOCH, STEVE);
		game.frame(EPOCH + 60, STEVE);
		game.frame(EPOCH + 180, STEVE);
		assertEquals(2, game.gpu.written.size());
		assertArrayEquals(HD.frame(1, 16), game.gpu.written.get(0), "box-filtered to 16 px per unit");
		assertArrayEquals(HD.frame(2, 16), game.gpu.written.get(1));
	}

	@Test
	void framesOverTheCapesBudgetAreHalvedIntoATextureOfTheirOwn(@TempDir Path cache) {
		Game game = new Game(cache, new Store(), Runnable::run, StoreCapes.MAX_FRAME_BYTES, 1024 * 1024);
		game.store.wear(STEVE, STANDARD);
		game.run(PLAYING);
		String poster = STANDARD.texture() + "#1";
		String own = STANDARD.texture() + "-8#2";
		assertEquals(List.of(poster, own), game.gpu.uploaded);
		assertArrayEquals(new int[] {512, 256}, game.gpu.sizes.get(own), "64x32 at 8 px per unit");
		assertEquals(own, game.capes.texture(STEVE), "the cape is drawn with the animation's texture");
		assertEquals(List.of(poster), game.gpu.released, "the poster's texture goes once nobody is handed it");
		assertEquals(List.of(own), game.gpu.writtenTo, "frame 0 written in at once");
		assertArrayEquals(STANDARD.frame(0, 8), game.gpu.written.get(0));
		game.frame(EPOCH, STEVE);
		game.frame(EPOCH + 40, STEVE);
		assertArrayEquals(STANDARD.frame(1, 8), game.gpu.written.get(1));
		assertEquals(CapeFrames.bytes(10, 8), game.capes.framesInMemory());
	}

	@Test
	void aStripThatFailsItsChecksLeavesThePosterAndIsntRetriedSoon(@TempDir Path cache) {
		Game game = wearing(cache, STANDARD);
		byte[] tampered = STANDARD.strips().get(1).clone();
		tampered[tampered.length - 20] ^= 1;
		game.store.files.put(STANDARD.stripUrls().get(1), tampered);
		game.run(PLAYING);
		assertEquals(STANDARD.texture() + "#1", game.capes.texture(STEVE), "the poster stays");
		assertEquals(0, game.capes.framesInMemory(), "no frames kept");
		game.frame(EPOCH, STEVE);
		game.frame(EPOCH + 40, STEVE);
		assertTrue(game.gpu.written.isEmpty(), "a static cape");
		long downloads = game.store.stripDownloads();
		game.run(20 * 60);
		assertEquals(downloads, game.store.stripDownloads(), "not retried within 30 minutes");
		game.store.files.put(STANDARD.stripUrls().get(1), STANDARD.strips().get(1));
		game.run(11 * 60);
		game.frame(EPOCH + 1030, STEVE);
		game.frame(EPOCH + 1030 + 40, STEVE);
		assertEquals(1, game.gpu.written.size(), "fixed on the Store: played after the wait");
	}

	@Test
	void aStripOfAnotherSizeThanTheManifestSaysLeavesThePoster(@TempDir Path cache) {
		Game game = wearing(cache, STANDARD);
		byte[] small = CapePng.encodeRgba(352, 272, AnimatedCapeFixture.frame(0, 10, 16));
		String hash = StoreCapes.sha256(small);
		String url = AnimatedCapeFixture.BUCKET + "animated/" + hash + ".png";
		game.store.files.put(url, small);
		// The Store's entry for the second strip, pointing at a PNG of one frame where three are promised.
		String animation = STANDARD.animation().replace(StoreCapes.sha256(STANDARD.strips().get(1)), hash)
			.replace("\"bytes\":" + STANDARD.strips().get(1).length, "\"bytes\":" + small.length);
		game.store.wearing.put(STEVE, STANDARD.asset(animation));
		game.run(PLAYING);
		assertNotNull(game.capes.texture(STEVE));
		assertEquals(2, game.store.stripDownloads(), "both strips came, the second refused");
		assertEquals(0, game.capes.framesInMemory());
		game.frame(EPOCH, STEVE);
		game.frame(EPOCH + 40, STEVE);
		assertTrue(game.gpu.written.isEmpty());
	}

	@Test
	void stripsThatCantBeReachedAreRetriedWithBackoff(@TempDir Path cache) {
		Game game = wearing(cache, STANDARD);
		game.store.stripsOffline = true;
		game.run(PLAYING);
		assertNotNull(game.capes.texture(STEVE), "the poster shows meanwhile");
		assertEquals(1, game.store.stripDownloads(), "the first strip failed: the rest isn't tried");
		game.run(30);
		assertEquals(1, game.store.stripDownloads(), "waits a minute");
		game.run(40);
		assertEquals(2, game.store.stripDownloads(), "then tries again");
		game.store.stripsOffline = false;
		game.run(2 * 60 + 5);
		assertEquals(CapeFrames.bytes(10, 16), game.capes.framesInMemory(), "back online: played");
	}

	@Test
	void aManifestThatDoesntCheckOutShowsThePosterAsAStaticCape(@TempDir Path cache) {
		Game game = new Game(cache);
		game.store.serve(STANDARD);
		game.store.wearing.put(STEVE, STANDARD.asset(STANDARD.animation().replace("\"v\":1", "\"v\":2")));
		game.run(PLAYING);
		assertEquals("store-capes/" + STANDARD.posterSha() + "#1", game.capes.texture(STEVE), "a static cape of the poster");
		assertEquals(0, game.store.stripDownloads(), "no strip is downloaded");
		game.frame(EPOCH, STEVE);
		game.frame(EPOCH + 40, STEVE);
		assertTrue(game.gpu.written.isEmpty());

		Game wrongPoster = new Game(cache);
		wrongPoster.store.serve(STANDARD);
		// A 64x32 poster for a 16 px per unit animation: not its frame 0.
		wrongPoster.store.wearing.put(STEVE, STANDARD.asset(STANDARD.animation()).replace("\"width\":1024,\"height\":512", "\"width\":64,\"height\":32"));
		wrongPoster.run(PLAYING);
		assertEquals(0, wrongPoster.store.stripDownloads());
	}

	@Test
	void everyWearerSharesOneTextureAndOneDownload(@TempDir Path cache) {
		Game game = wearing(cache, STANDARD);
		game.store.wear(ALEX, STANDARD);
		game.players = List.of(STEVE, ALEX);
		game.run(PLAYING);
		assertSame(game.capes.texture(STEVE), game.capes.texture(ALEX));
		assertEquals(1, game.gpu.uploaded.size());
		assertEquals(2, game.store.stripDownloads(), "each strip once");
		game.frame(EPOCH, STEVE, ALEX);
		game.frame(EPOCH + 40, STEVE, ALEX);
		assertEquals(1, game.gpu.written.size(), "one write for both");
	}

	@Test
	void switchingBetweenAnimatedAndStaticNeverShowsTheOtherOne(@TempDir Path cache) {
		Game game = wearing(cache, STANDARD);
		game.run(PLAYING);
		String animated = game.capes.texture(STEVE);
		assertEquals(STANDARD.texture() + "#1", animated);
		// The Store now answers the same poster without its animation (unequipped and a static one equipped, say).
		game.store.wearing.put(STEVE, STANDARD.asset("null"));
		game.run(61);
		String still = game.capes.texture(STEVE);
		assertEquals("store-capes/" + STANDARD.posterSha() + "#2", still, "the static cape: a texture of its own");
		game.frame(EPOCH, STEVE);
		game.frame(EPOCH + 40, STEVE);
		assertTrue(game.gpu.written.isEmpty(), "nothing animates on Steve");
		game.run(3 * 60);
		assertTrue(game.gpu.released.contains(animated), "the animation, worn by nobody, is released");
		assertEquals(0, game.capes.framesInMemory());

		long downloads = game.store.stripDownloads();
		game.store.wear(STEVE, STANDARD);
		game.run(70);
		String again = game.capes.texture(STEVE);
		assertNotEquals(animated, again);
		assertEquals(STANDARD.texture() + "#3", again, "animated again, as a new texture");
		assertEquals(downloads, game.store.stripDownloads(), "its strips read from the disk cache");
		game.frame(EPOCH, STEVE);
		game.frame(EPOCH + 40, STEVE);
		assertEquals(List.of(again), game.gpu.writtenTo);
	}

	@Test
	void framesAndTexturesGoWhenTheirWearerLeaves(@TempDir Path cache) {
		Game game = wearing(cache, STANDARD);
		game.run(PLAYING);
		String texture = game.capes.texture(STEVE);
		game.players = List.of();
		game.run(2 * 60 + 2);
		assertEquals(List.of(texture), game.gpu.released);
		assertEquals(0, game.capes.framesInMemory());
		assertNull(game.capes.texture(STEVE));
		game.frame(EPOCH, STEVE);
		game.frame(EPOCH + 40, STEVE);
		assertTrue(game.gpu.written.isEmpty(), "a released texture is never written");
	}

	@Test
	void turningStoreCapesOffOrAnotherStoreFreesEverything(@TempDir Path cache) {
		Game game = wearing(cache, STANDARD);
		game.run(PLAYING);
		String texture = game.capes.texture(STEVE);
		game.capes.update(game.now, false, List.of(STEVE));
		assertEquals(List.of(texture), game.gpu.released);
		assertEquals(0, game.capes.framesInMemory());
		game.frame(EPOCH, STEVE);
		game.frame(EPOCH + 40, STEVE);
		assertTrue(game.gpu.written.isEmpty());
		assertNull(game.capes.texture(STEVE));
		assertFalse(game.capes.wears(STEVE));
	}

	@Test
	void overTheBudgetACapeKeepsItsPosterUntilAnUnwornOneMakesRoom(@TempDir Path cache) {
		Cape two = Cape.of("two", 4, new int[] {100, 100});
		Cape three = Cape.of("three", 4, new int[] {100, 100, 100});
		Cape four = Cape.of("four", 4, new int[] {100, 100, 100, 100});
		Game game = new Game(cache, new Store(), Runnable::run, CapeFrames.bytes(7, 4), CapeFrames.MAX_BYTES);
		game.store.wear(STEVE, two);
		game.store.wear(ALEX, three);
		game.store.wear(NOTCH, four);
		game.players = List.of(STEVE, ALEX);
		game.run(5);
		assertEquals(CapeFrames.bytes(5, 4), game.capes.framesInMemory(), "two and three fit");
		game.players = List.of(STEVE, ALEX, NOTCH);
		game.run(5);
		assertNotNull(game.capes.texture(NOTCH), "four shows its poster");
		assertEquals(CapeFrames.bytes(5, 4), game.capes.framesInMemory(), "both others are worn in view: four waits");
		assertEquals(2, game.store.stripDownloads(), "four's strip isn't downloaded while it can't be kept");
		game.players = List.of(ALEX, NOTCH);
		game.run(2);
		assertEquals(CapeFrames.bytes(7, 4), game.capes.framesInMemory(), "Steve left: his cape made room for four");
		assertTrue(game.gpu.released.contains(two.texture() + "#1"));
		long start = EPOCH - EPOCH % 1200;
		game.frame(start, ALEX, NOTCH);
		game.frame(start + 100, ALEX, NOTCH);
		assertEquals(2, game.gpu.written.size(), "three and four play");
		game.players = List.of(STEVE, ALEX, NOTCH);
		game.run(5);
		assertNotNull(game.capes.texture(STEVE), "Steve's back: his poster again");
		assertEquals(CapeFrames.bytes(7, 4), game.capes.framesInMemory(), "and his animation waits for room");
	}

	@Test
	void stripsLoadOneATaskSoOtherCapesDontWaitForAWholeAnimation(@TempDir Path cache) {
		ArrayDeque<Runnable> queue = new ArrayDeque<>();
		Store store = new Store();
		Game game = new Game(cache, store, queue::add, StoreCapes.MAX_FRAME_BYTES, CapeFrames.MAX_BYTES);
		store.wear(STEVE, HD);
		Cape other = Cape.of("other", 4, new int[] {100, 100});
		store.serve(other);
		store.wearing.put(ALEX, other.asset("null"));
		game.run(3);
		assertEquals(1, queue.size(), "Steve's poster");
		drain(queue);
		game.run(1);
		assertTrue(queue.isEmpty());
		game.run(1);
		assertEquals(1, queue.size(), "his first strip, queued once his poster is up");
		queue.poll().run();
		assertEquals(1, queue.size(), "then his second strip");
		game.players = List.of(STEVE, ALEX);
		game.run(3);
		assertEquals(2, queue.size(), "Alex's poster joins the queue behind it");
		drain(queue);
		List<String> order = new ArrayList<>();
		for (URI uri : store.downloads) {
			order.add(uri.toString());
		}
		assertEquals(List.of(HD.posterUrl(), HD.stripUrls().get(0), HD.stripUrls().get(1), other.posterUrl(), HD.stripUrls().get(2)), order,
			"Alex's poster before Steve's last strip");
		game.run(2);
		assertEquals(CapeFrames.bytes(8, 16), game.capes.framesInMemory());
		assertNotNull(game.capes.texture(ALEX));
	}

	private static void drain(ArrayDeque<Runnable> queue) {
		while (!queue.isEmpty()) {
			queue.poll().run();
		}
	}

	@Test
	void aCapeReleasedWhileItsStripsLoadIsntFinished(@TempDir Path cache) {
		ArrayDeque<Runnable> queue = new ArrayDeque<>();
		Store store = new Store();
		Game game = new Game(cache, store, queue::add, StoreCapes.MAX_FRAME_BYTES, CapeFrames.MAX_BYTES);
		store.wear(STEVE, STANDARD);
		game.run(3);
		drain(queue);
		game.run(2);
		queue.poll().run();
		game.capes.update(game.now, false, List.of());
		drain(queue);
		assertEquals(1, store.stripDownloads(), "cancelled between strips");
		assertEquals(0, game.capes.framesInMemory());
	}

	@Test
	void stripsAreCachedByTheirHash(@TempDir Path cache) throws Exception {
		Game first = wearing(cache, STANDARD);
		first.run(PLAYING);
		for (byte[] strip : STANDARD.strips()) {
			assertArrayEquals(strip, Files.readAllBytes(cache.resolve(StoreCapes.sha256(strip) + ".png")));
		}
		Game second = new Game(cache, first.store, Runnable::run, StoreCapes.MAX_FRAME_BYTES, CapeFrames.MAX_BYTES);
		second.run(PLAYING);
		assertEquals(CapeFrames.bytes(10, 16), second.capes.framesInMemory());
		assertEquals(2, first.store.stripDownloads(), "the next launch reads them from the cache");

		Files.write(cache.resolve(StoreCapes.sha256(STANDARD.strips().get(0)) + ".png"), new byte[] {1, 2, 3});
		Game third = new Game(cache, first.store, Runnable::run, StoreCapes.MAX_FRAME_BYTES, CapeFrames.MAX_BYTES);
		third.run(PLAYING);
		assertEquals(3, first.store.stripDownloads(), "a changed file is downloaded again");
		assertEquals(CapeFrames.bytes(10, 16), third.capes.framesInMemory());
	}

	@Test
	void aFailedWriteStopsTheAnimationNotTheGame(@TempDir Path cache) {
		Game game = wearing(cache, STANDARD);
		game.run(PLAYING);
		game.gpu.failWrites = true;
		game.frame(EPOCH, STEVE);
		game.frame(EPOCH + 40, STEVE);
		game.frame(EPOCH + 120, STEVE);
		assertEquals(STANDARD.texture() + "#1", game.capes.texture(STEVE), "the cape stays, still");
		assertEquals(0, game.capes.framesInMemory());
		game.gpu.failWrites = false;
		game.frame(EPOCH + 240, STEVE);
		assertTrue(game.gpu.written.isEmpty());
	}

	@Test
	void describeSaysWhatPlays(@TempDir Path cache) {
		Game game = wearing(cache, STANDARD);
		game.run(PLAYING);
		game.frame(EPOCH, STEVE);
		game.frame(EPOCH + 40, STEVE);
		String line = game.capes.describe();
		assertTrue(line.contains("1 animated (1 playing, 3 MiB of frames, 1 frame writes of "), line);
	}
}
