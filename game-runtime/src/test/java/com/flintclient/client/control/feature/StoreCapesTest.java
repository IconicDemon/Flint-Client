package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.store.StoreCosmeticsClient;
import com.flintclient.client.control.store.StoreCosmeticsClient.Response;
import com.flintclient.client.control.store.StoreEndpoint;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreCapesTest {
	private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
	private static final UUID ALEX = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
	/** An offline-mode player's id (version 3): no Mojang account, never sent to the Store. */
	private static final UUID OFFLINE = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8));
	private static final String EMBER = "https://store.flintclient.com/test-capes/ember-cape.png";
	private static final String ASSET_ID = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff";
	private static final long T = 10_000_000_000_000L;

	private static long s(long seconds) {
		return TimeUnit.SECONDS.toNanos(seconds);
	}

	/** The Store as the game sees it: who wears what, the files it serves, and how it answers. */
	private static final class Store implements StoreCosmeticsClient.Transport {
		final Map<UUID, String> wearing = new HashMap<>();
		final Map<String, byte[]> files = new HashMap<>();
		final List<List<UUID>> lookups = new ArrayList<>();
		final List<URI> downloads = new ArrayList<>();
		final List<Boolean> freshLookups = new ArrayList<>();
		int status = 200;
		String cacheControl = "public, max-age=60";
		String retryAfter;
		String body;
		boolean offline;

		@Override
		public CompletableFuture<Response> get(URI uri, String accept, int maxBytes, boolean fresh) {
			if (uri.getPath().equals("/api/v1/players/cosmetics/")) {
				freshLookups.add(fresh);
				List<UUID> asked = new ArrayList<>();
				for (String id : uri.getRawQuery().substring("uuids=".length()).split(",")) {
					asked.add(UUID.fromString(id));
				}
				lookups.add(asked);
				if (offline) {
					return CompletableFuture.failedFuture(new ConnectException("no route"));
				}
				StringJoiner players = new StringJoiner(",");
				for (UUID id : asked) {
					if (wearing.containsKey(id)) {
						players.add("\"" + id + "\":{\"cape\":{\"cosmetic_id\":\"11111111-2222-4333-8444-555555555555\",\"kind\":\"cape\","
							+ "\"asset\":" + wearing.get(id) + "}}");
					}
				}
				String answer = body != null ? body : "{\"players\":{" + players + "}}";
				return CompletableFuture.completedFuture(new Response(status, answer.getBytes(StandardCharsets.UTF_8), cacheControl, retryAfter));
			}
			downloads.add(uri);
			if (offline) {
				return CompletableFuture.failedFuture(new ConnectException("no route"));
			}
			byte[] file = files.get(uri.toString());
			return CompletableFuture.completedFuture(file == null ? new Response(404, new byte[0], null, null) : new Response(200, file, null, null));
		}

		/** {@code player} wears the cape at {@code url} whose bytes are {@code png}, as the Store describes it. */
		void wear(UUID player, String url, byte[] png, String sha256, int width, int height) {
			files.put(url, png);
			wearing.put(player, "{\"id\":\"" + ASSET_ID + "\",\"version\":1,\"path\":\"/test-capes/x.png\",\"sha256\":\"" + sha256
				+ "\",\"width\":" + width + ",\"height\":" + height + ",\"url\":\"" + url + "\"}");
		}
	}

	/** Textures as names, with what was uploaded and released. */
	private static final class Gpu implements StoreCapes.Textures<String> {
		final List<String> uploaded = new ArrayList<>();
		final Map<String, int[]> pixels = new HashMap<>();
		final List<String> released = new ArrayList<>();

		@Override
		public String upload(String name, int width, int height, int[] argb) {
			String texture = name + "#" + (uploaded.size() + 1);
			uploaded.add(texture);
			pixels.put(texture, argb);
			return texture;
		}

		@Override
		public void write(String texture, int[] pixels, int width, int height) {
			throw new AssertionError("a static cape is never written to");
		}

		@Override
		public void release(String texture) {
			released.add(texture);
		}
	}

	/** A service on a fake Store, stepped one second at a time (as the game updates it). */
	private static final class Game {
		final Store store = new Store();
		final Gpu gpu = new Gpu();
		final StoreCapes<String> capes;
		long now = T;
		List<UUID> players = List.of(STEVE);
		boolean on = true;

		Game(Path cache) {
			capes = new StoreCapes<>(new StoreCosmeticsClient(StoreEndpoint.defaults(), store), cache, gpu, Runnable::run);
		}

		Game(Path cache, Store store) {
			capes = new StoreCapes<>(new StoreCosmeticsClient(StoreEndpoint.defaults(), store), cache, gpu, Runnable::run);
		}

		/** {@code seconds} updates, one a second, each followed by a tick's uploads. */
		void run(long seconds) {
			for (long i = 0; i < seconds; i++) {
				capes.update(now, on, players);
				capes.upload(2);
				now += s(1);
			}
		}
	}

	private static final byte[] PNG = CapePng.encodeRgba(64, 32, CapePngTest.capePixels(64, 32, 5));
	private static final String SHA = StoreCapes.sha256(PNG);

	private static Game wearing(Path cache) {
		Game game = new Game(cache);
		game.store.wear(STEVE, EMBER, PNG, SHA, 64, 32);
		return game;
	}

	@Test
	void aCapeIsShownOnlyOnceTheStoreAnswersAndItsTextureChecksOut(@TempDir Path cache) {
		Game game = wearing(cache);
		game.players = List.of(STEVE, ALEX, OFFLINE);
		game.run(1);
		assertTrue(game.store.lookups.isEmpty(), "a newly seen player waits a moment, so others arriving share the request");
		game.run(3);
		assertEquals(List.of(List.of(STEVE, ALEX)), game.store.lookups, "one request, Mojang accounts only");
		assertEquals(List.of(URI.create(EMBER)), game.store.downloads);
		String texture = game.capes.texture(STEVE);
		assertEquals("store-capes/" + SHA + "#1", texture);
		assertArrayEquals(CapePngTest.capePixels(64, 32, 5), game.gpu.pixels.get(texture));
		assertNull(game.capes.texture(ALEX), "the Store said nothing about Alex: no cape");
		assertNull(game.capes.texture(OFFLINE));
		assertTrue(Files.isRegularFile(cache.resolve(SHA + ".png")), "the verified texture is cached by its hash");
	}

	@Test
	void playersArrivingTogetherShareRequestsOfAtMostOneHundred(@TempDir Path cache) {
		Game game = new Game(cache);
		List<UUID> crowd = new ArrayList<>();
		for (int i = 0; i < 150; i++) {
			crowd.add(new UUID(0x1234_5678_9abc_4defL, 0x8000_0000_0000_0000L | i));
		}
		game.players = crowd;
		game.run(3);
		assertEquals(2, game.store.lookups.size());
		assertEquals(100, game.store.lookups.get(0).size());
		assertEquals(50, game.store.lookups.get(1).size());
		assertEquals(crowd.subList(0, 100), game.store.lookups.get(0), "the nearest first");
		game.run(55);
		assertEquals(2, game.store.lookups.size(), "nobody is asked about again within max-age");
	}

	@Test
	void answersAreKeptForTheStoresMaxAgeThenAskedForAgain(@TempDir Path cache) {
		Game game = wearing(cache);
		game.run(4);
		assertEquals(1, game.store.lookups.size());
		// Answered at T+2 with max-age=60: asked again at T+62, not before.
		game.run(58);
		assertEquals(1, game.store.lookups.size());
		game.run(1);
		assertEquals(2, game.store.lookups.size());
		assertEquals(1, game.store.downloads.size(), "the same asset isn't downloaded again");
		assertNotNull(game.capes.texture(STEVE));
	}

	@Test
	void anUnequippedOrRevokedCapeGoesAtTheNextRefresh(@TempDir Path cache) {
		Game game = wearing(cache);
		game.run(4);
		String texture = game.capes.texture(STEVE);
		assertNotNull(texture);
		game.store.wearing.remove(STEVE);
		game.run(58);
		assertEquals(texture, game.capes.texture(STEVE), "until the refresh, the last answer stands");
		game.run(2);
		assertNull(game.capes.texture(STEVE), "the Store no longer lists it: gone");
		assertTrue(game.gpu.released.isEmpty(), "released only once nobody has worn it for a while");
		game.run(StoreCapes.RELEASE_NANOS / s(1) + 2);
		assertEquals(List.of(texture), game.gpu.released);
	}

	@Test
	void aTextureWhoseHashDoesntMatchIsNeverShownNorRetriedSoon(@TempDir Path cache) {
		Game game = new Game(cache);
		byte[] other = CapePng.encodeRgba(64, 32, CapePngTest.capePixels(64, 32, 99));
		game.store.wear(STEVE, EMBER, other, SHA, 64, 32);
		game.run(10);
		assertEquals(1, game.store.downloads.size());
		assertNull(game.capes.texture(STEVE));
		assertTrue(game.gpu.uploaded.isEmpty());
		assertFalse(Files.exists(cache.resolve(SHA + ".png")), "nothing unverified is cached");
		game.run(StoreCapes.ASSET_REFUSED_NANOS / s(1) - 20);
		assertEquals(1, game.store.downloads.size(), "not tried again for half an hour");
		game.run(30);
		assertEquals(2, game.store.downloads.size());
	}

	@Test
	void aMalformedAssetIsRefusedEvenWhenItsHashMatches(@TempDir Path cache) {
		Game game = new Game(cache);
		byte[] junk = "<html>not a png at all, but exactly the bytes the Store hashed</html>".getBytes(StandardCharsets.UTF_8);
		game.store.wear(STEVE, EMBER, junk, StoreCapes.sha256(junk), 64, 32);
		game.run(10);
		assertEquals(1, game.store.downloads.size());
		assertNull(game.capes.texture(STEVE));
		assertTrue(game.gpu.uploaded.isEmpty());
		assertFalse(Files.exists(cache.resolve(StoreCapes.sha256(junk) + ".png")), "a file that isn't a texture isn't cached");
		byte[] truncated = java.util.Arrays.copyOf(PNG, PNG.length - 20);
		Game cut = new Game(cache);
		cut.store.wear(STEVE, EMBER, truncated, StoreCapes.sha256(truncated), 64, 32);
		cut.run(10);
		assertNull(cut.capes.texture(STEVE));
		assertTrue(cut.gpu.uploaded.isEmpty());
	}

	@Test
	void switchingAccountsNeverCarriesACapeOver(@TempDir Path cache) {
		// The account card switches accounts in game: your id changes, and only the new account's own answer counts.
		Game game = wearing(cache);
		game.run(4);
		assertNotNull(game.capes.texture(STEVE));
		game.players = List.of(ALEX);
		game.run(4);
		assertNull(game.capes.texture(ALEX), "Alex wears nothing: Steve's cape isn't his");
		assertEquals(List.of(ALEX), game.store.lookups.get(1), "the new account is asked about on its own");
		game.capes.refresh(ALEX);
		game.run(10);
		assertNull(game.capes.texture(ALEX));
		assertTrue(game.store.lookups.stream().skip(1).allMatch(asked -> asked.equals(List.of(ALEX))),
			"the old account isn't asked about once it's gone");
		byte[] frost = CapePng.encodeRgba(64, 32, CapePngTest.capePixels(64, 32, 41));
		game.store.wear(ALEX, "https://store.flintclient.com/test-capes/frost-cape.png", frost, StoreCapes.sha256(frost), 64, 32);
		game.capes.refresh(ALEX);
		game.run(4);
		assertArrayEquals(CapePngTest.capePixels(64, 32, 41), game.gpu.pixels.get(game.capes.texture(ALEX)), "his own cape, once he equips one");
	}

	@Test
	void aTextureOfAnotherSizeThanTheStoreSaidIsRefused(@TempDir Path cache) {
		Game game = new Game(cache);
		byte[] hd = CapePng.encodeRgba(128, 64, CapePngTest.capePixels(128, 64, 3));
		game.store.wear(STEVE, EMBER, hd, StoreCapes.sha256(hd), 64, 32);
		game.run(10);
		assertNull(game.capes.texture(STEVE));
		assertTrue(game.gpu.uploaded.isEmpty());
	}

	@Test
	void anHdCapeOfTheRightSizeIsShown(@TempDir Path cache) {
		Game game = new Game(cache);
		byte[] hd = CapePng.encodeRgba(256, 128, CapePngTest.capePixels(256, 128, 3));
		game.store.wear(STEVE, EMBER, hd, StoreCapes.sha256(hd), 256, 128);
		game.run(4);
		assertEquals(256 * 128, game.gpu.pixels.get(game.capes.texture(STEVE)).length);
	}

	@Test
	void offlineTheLastVerifiedCapeStaysForAWhileThenGoes(@TempDir Path cache) {
		Game game = wearing(cache);
		game.run(4);
		assertNotNull(game.capes.texture(STEVE));
		game.store.offline = true;
		game.run(59);
		assertEquals(2, game.store.lookups.size(), "the refresh at T+62 failed");
		assertNotNull(game.capes.texture(STEVE), "kept while the Store can't be reached");
		game.run(29);
		assertEquals(2, game.store.lookups.size(), "backing off: 30 s before the next try");
		game.run(2);
		assertEquals(3, game.store.lookups.size());
		game.run(60);
		assertEquals(3, game.store.lookups.size(), "then 60 s");
		// The answer was given at T+2; half an hour later it's too old to trust.
		long left = (T + s(2) + StoreCapes.STALE_NANOS - game.now) / s(1);
		game.run(left - 1);
		assertNotNull(game.capes.texture(STEVE));
		game.run(3);
		assertNull(game.capes.texture(STEVE), "dropped: no Store answer for half an hour");
		game.store.offline = false;
		game.run(StoreCapes.MAX_RETRY_NANOS / s(1) + 4);
		assertNotNull(game.capes.texture(STEVE), "back once the Store answers again");
	}

	@Test
	void aBusyStoreIsLeftAloneForItsRetryAfter(@TempDir Path cache) {
		Game game = wearing(cache);
		game.store.status = 429;
		game.store.retryAfter = "120";
		game.run(2);
		assertEquals(1, game.store.lookups.size());
		game.store.status = 200;
		game.run(119);
		assertEquals(1, game.store.lookups.size());
		game.run(4);
		assertEquals(2, game.store.lookups.size());
		assertNotNull(game.capes.texture(STEVE));
	}

	@Test
	void aServerErrorBacksOffAndKeepsWhatWasShown(@TempDir Path cache) {
		Game game = wearing(cache);
		game.run(4);
		String texture = game.capes.texture(STEVE);
		game.store.status = 503;
		game.run(60);
		assertEquals(2, game.store.lookups.size());
		assertEquals(texture, game.capes.texture(STEVE));
		game.run(20);
		assertEquals(2, game.store.lookups.size());
	}

	@Test
	void aMalformedAnswerChangesNothingAndBacksOff(@TempDir Path cache) {
		Game game = wearing(cache);
		game.run(4);
		String texture = game.capes.texture(STEVE);
		game.store.body = "{\"players\": not json";
		game.run(60);
		assertEquals(2, game.store.lookups.size());
		assertEquals(texture, game.capes.texture(STEVE), "a broken answer isn't an empty wardrobe");
		game.run(20);
		assertEquals(2, game.store.lookups.size(), "backing off");
	}

	@Test
	void aCapeFromAnAddressTheStoreDoesntUseIsNeitherDownloadedNorShown(@TempDir Path cache) {
		Game game = new Game(cache);
		game.store.wear(STEVE, "https://evil.example/cape.png", PNG, SHA, 64, 32);
		game.run(10);
		assertEquals(1, game.store.lookups.size());
		assertTrue(game.store.downloads.isEmpty(), "never requested");
		assertNull(game.capes.texture(STEVE));
	}

	@Test
	void turningStoreCapesOffReleasesEverythingAndAsksNobody(@TempDir Path cache) {
		Game game = wearing(cache);
		game.run(4);
		String texture = game.capes.texture(STEVE);
		game.on = false;
		game.run(1);
		assertNull(game.capes.texture(STEVE));
		assertEquals(List.of(texture), game.gpu.released);
		game.run(300);
		assertEquals(1, game.store.lookups.size(), "off: the Store isn't asked");
		game.on = true;
		game.run(4);
		assertEquals(2, game.store.lookups.size());
		assertNotNull(game.capes.texture(STEVE));
		assertEquals(1, game.store.downloads.size(), "the cached texture is used");
	}

	@Test
	void theDiskCacheIsUsedOnlyWhileItsHashStillMatches(@TempDir Path cache) throws Exception {
		Game first = wearing(cache);
		first.run(4);
		assertEquals(1, first.store.downloads.size());
		Path file = cache.resolve(SHA + ".png");
		assertArrayEquals(PNG, Files.readAllBytes(file));

		Game second = new Game(cache, first.store);
		second.run(4);
		assertNotNull(second.capes.texture(STEVE));
		assertEquals(1, first.store.downloads.size(), "read from the cache, checked against the Store's hash");

		Files.write(file, CapePng.encodeRgba(64, 32, CapePngTest.capePixels(64, 32, 77)));
		Game third = new Game(cache, first.store);
		third.run(4);
		assertNotNull(third.capes.texture(STEVE));
		assertEquals(2, first.store.downloads.size(), "a changed file is never used: downloaded again");
		assertArrayEquals(CapePngTest.capePixels(64, 32, 5), third.gpu.pixels.get(third.capes.texture(STEVE)));
		assertArrayEquals(PNG, Files.readAllBytes(file), "and replaced with the verified one");
	}

	@Test
	void aLocalFileAloneNeverMakesACapeAppear(@TempDir Path cache) throws Exception {
		Files.write(cache.resolve(SHA + ".png"), PNG);
		Game game = new Game(cache);
		game.run(10);
		assertEquals(1, game.store.lookups.size());
		assertNull(game.capes.texture(STEVE), "cached textures are only drawn for what the Store says a player wears");
		assertTrue(game.gpu.uploaded.isEmpty());
	}

	@Test
	void theLaunchersSignalAsksAboutYouAgainBeforeMaxAge(@TempDir Path cache) {
		Game game = wearing(cache);
		game.run(20);
		assertEquals(1, game.store.lookups.size());
		game.capes.refresh(STEVE);
		game.run(1);
		assertEquals(2, game.store.lookups.size(), "asked again at once, long before max-age");
		assertEquals(java.util.List.of(false, true), game.store.freshLookups, "past any cache, as the launcher's handoff asks");
		game.capes.refresh(STEVE);
		game.run(4);
		assertEquals(2, game.store.lookups.size(), "but not more than every 5 s");
		game.run(2);
		assertEquals(3, game.store.lookups.size());
		assertEquals(java.util.List.of(false, true, true), game.store.freshLookups);
		game.run(30);
		assertEquals(3, game.store.lookups.size(), "no signal: max-age again");
	}

	@Test
	void onlyAStoreOnThisComputerIsAskedAboutOfflineIds(@TempDir Path cache) {
		Game game = new Game(cache);
		game.players = List.of(OFFLINE);
		game.run(10);
		assertTrue(game.store.lookups.isEmpty(), "the real Store never hears of offline-mode players");

		Store local = new Store();
		String url = "http://127.0.0.1:8787/test-capes/ember-cape.png";
		local.wear(OFFLINE, url, PNG, SHA, 64, 32);
		Gpu gpu = new Gpu();
		StoreCapes<String> capes = new StoreCapes<>(new StoreCosmeticsClient(StoreEndpoint.parse("http://127.0.0.1:8787", "test"), local), cache,
			gpu, Runnable::run);
		for (int i = 0; i < 4; i++) {
			capes.update(T + s(i), true, List.of(OFFLINE));
			capes.upload(2);
		}
		assertEquals(List.of(List.of(OFFLINE)), local.lookups);
		assertNotNull(capes.texture(OFFLINE), "a local Store (development, QA's offline identity) may be asked about anyone");
	}

	@Test
	void aPlayerOutOfViewIsForgotten(@TempDir Path cache) {
		Game game = wearing(cache);
		game.players = List.of(STEVE, ALEX);
		game.run(4);
		game.players = List.of(STEVE);
		game.run(StoreCapes.FORGET_NANOS / s(1) + 2);
		assertEquals(java.util.Set.of(STEVE), game.capes.known());
	}

	@Test
	void anElytraWearsTheCapeOnlyWhenTheCapeDrawsWings(@TempDir Path cache) {
		// The Store's test capes (and every animated cape's poster) leave the elytra's part (x 22-45, y 0-21) transparent.
		int[] wingless = CapePngTest.capePixels(64, 32, 5);
		assertFalse(StoreCapes.hasWings(64, 32, wingless));
		int[] winged = wingless.clone();
		winged[10 * 64 + 30] = 0xFF336699;
		assertTrue(StoreCapes.hasWings(64, 32, winged));
		// At any scale: an HD cape's wings are 8 times as far in.
		int[] hd = CapePngTest.capePixels(512, 256, 5);
		assertFalse(StoreCapes.hasWings(512, 256, hd));
		hd[(21 * 8 + 7) * 512 + 45 * 8 + 7] = 0x80FFFFFF;
		assertTrue(StoreCapes.hasWings(512, 256, hd));
		// Pixels outside it (the cape itself, the rest of the texture) don't count; neither does a texture that isn't 64x32 per unit.
		int[] edge = wingless.clone();
		edge[22 * 64 + 30] = 0xFFFFFFFF;
		edge[5 * 64 + 46] = 0xFFFFFFFF;
		assertFalse(StoreCapes.hasWings(64, 32, edge));
		assertFalse(StoreCapes.hasWings(64, 64, new int[64 * 64]));

		// The service says so for each wearer.
		Game game = new Game(cache);
		byte[] withWings = CapePng.encodeRgba(64, 32, winged);
		game.store.wear(STEVE, EMBER, PNG, SHA, 64, 32);
		game.store.wear(ALEX, "https://store.flintclient.com/test-capes/frost-cape.png", withWings, StoreCapes.sha256(withWings), 64, 32);
		game.players = List.of(STEVE, ALEX);
		game.run(4);
		assertNotNull(game.capes.texture(STEVE));
		assertNotNull(game.capes.texture(ALEX));
		assertFalse(game.capes.wings(STEVE), "no elytra art: the wings keep what Minecraft gives them");
		assertTrue(game.capes.wings(ALEX));
		assertFalse(game.capes.wings(OFFLINE), "wears nothing");
	}
}
