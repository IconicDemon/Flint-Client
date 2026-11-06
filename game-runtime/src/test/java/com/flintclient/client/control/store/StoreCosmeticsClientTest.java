package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.feature.AnimatedCapeFixture;
import com.flintclient.client.control.store.StoreCosmeticsClient.Asset;
import com.flintclient.client.control.store.StoreCosmeticsClient.Lookup;
import com.flintclient.client.control.store.StoreCosmeticsClient.Response;
import com.flintclient.client.control.store.StoreCosmeticsClient.Status;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class StoreCosmeticsClientTest {
	static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
	static final UUID ALEX = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
	static final String SHA = "9164506a34433704262b961558bb623767caf1677848b790f7e4255815089e3b";
	static final String ASSET_ID = "6f9619ff-8b86-4d01-b42d-00cf4fc964ff";
	static final String EMBER = "https://store.flintclient.com/test-capes/ember-cape.png";

	/** Answers by address, keeping every request. */
	static final class Fake implements StoreCosmeticsClient.Transport {
		final List<URI> uris = new ArrayList<>();
		final List<Integer> caps = new ArrayList<>();
		final List<Boolean> fresh = new ArrayList<>();
		Function<URI, CompletableFuture<Response>> answer;

		Fake(Function<URI, CompletableFuture<Response>> answer) {
			this.answer = answer;
		}

		@Override
		public CompletableFuture<Response> get(URI uri, String accept, int maxBytes, boolean fresh) {
			uris.add(uri);
			this.fresh.add(fresh);
			caps.add(maxBytes);
			return answer.apply(uri);
		}
	}

	static CompletableFuture<Response> reply(int status, String body, String cacheControl, String retryAfter) {
		return CompletableFuture.completedFuture(new Response(status, body.getBytes(StandardCharsets.UTF_8), cacheControl, retryAfter));
	}

	static CompletableFuture<Response> json(String body) {
		return reply(200, body, "public, max-age=60", null);
	}

	/** A player's equipped cape as the Store's public lookup answers it. */
	static String cape(UUID player, String url, String sha, int width, int height) {
		return "\"" + player + "\":{\"cape\":{\"cosmetic_id\":\"11111111-2222-4333-8444-555555555555\",\"kind\":\"cape\",\"asset\":{\"id\":\""
			+ ASSET_ID + "\",\"version\":1,\"path\":\"/test-capes/ember-cape.png\",\"sha256\":\"" + sha + "\",\"width\":" + width
			+ ",\"height\":" + height + ",\"url\":\"" + url + "\"}}}";
	}

	private static StoreCosmeticsClient client(Fake fake) {
		return new StoreCosmeticsClient(StoreEndpoint.defaults(), fake);
	}

	@Test
	void aLookupAsksForAtMostOneHundredPlayersAndReadsTheirCapes() {
		Fake fake = new Fake(uri -> json("{\"players\":{" + cape(STEVE, EMBER, SHA, 64, 32) + "}}"));
		List<UUID> players = new ArrayList<>();
		players.add(STEVE);
		players.add(ALEX);
		for (int i = 0; i < 150; i++) {
			players.add(new UUID(0x1234_5678_9abc_4defL, 0x8000_0000_0000_0000L | i));
		}
		Lookup l = client(fake).lookup(players).join();
		assertEquals(Status.OK, l.status());
		assertEquals(60, l.maxAgeSeconds());
		assertEquals(1, fake.uris.size());
		String query = fake.uris.get(0).getRawQuery();
		assertTrue(query.startsWith("uuids=069a79f4-44e9-4726-a5be-fca90e38aaf5,853c80ef"), query);
		assertEquals(100, query.substring("uuids=".length()).split(",").length);
		assertEquals(StoreCosmeticsClient.MAX_LOOKUP_BYTES, fake.caps.get(0));
		assertEquals(List.of(false), fake.fresh, "an ordinary lookup may be served from a cache");
		client(fake).lookup(List.of(STEVE), true).join();
		assertEquals(List.of(false, true), fake.fresh, "after the launcher's signal: past any cache");
		Asset asset = l.capes().get(STEVE);
		assertEquals(new Asset(ASSET_ID, 1, URI.create(EMBER), SHA, 64, 32), asset);
		assertNull(l.capes().get(ALEX), "a player the answer leaves out wears nothing");
	}

	@Test
	void anEmptyListAsksNobody() {
		Fake fake = new Fake(uri -> json("{}"));
		assertEquals(Status.OK, client(fake).lookup(List.of()).join().status());
		assertTrue(fake.uris.isEmpty());
	}

	@Test
	void hdCapesAndTheSupabaseBucketAreAccepted() {
		String supabase = "https://abcdefghijklmnopqrst.supabase.co/storage/v1/object/public/cosmetic-assets/staff/u/c.png";
		Fake fake = new Fake(uri -> json("{\"players\":{" + cape(STEVE, supabase, SHA.toUpperCase(), 1024, 512) + "}}"));
		Asset asset = client(fake).lookup(List.of(STEVE)).join().capes().get(STEVE);
		assertEquals(1024, asset.width());
		assertEquals(SHA, asset.sha256(), "the hash is compared in lowercase");
	}

	@Test
	void anythingWrongWithAnAssetMeansNoCape() {
		String[] bad = {
			cape(STEVE, "https://evil.example/cape.png", SHA, 64, 32),
			cape(STEVE, "http://store.flintclient.com/test-capes/ember-cape.png", SHA, 64, 32),
			cape(STEVE, EMBER + "?x=1", SHA, 64, 32),
			cape(STEVE, EMBER, "deadbeef", 64, 32),
			cape(STEVE, EMBER, SHA, 64, 64),
			cape(STEVE, EMBER, SHA, 63, 32),
			cape(STEVE, EMBER, SHA, 2048, 1024),
			cape(STEVE, EMBER, SHA, 64, 32).replace("\"version\":1", "\"version\":0"),
			cape(STEVE, EMBER, SHA, 64, 32).replace("\"version\":1", "\"version\":1.5"),
			cape(STEVE, EMBER, SHA, 64, 32).replace(ASSET_ID, "not-a-uuid"),
			cape(STEVE, EMBER, SHA, 64, 32).replace("\"kind\":\"cape\"", "\"kind\":\"aura\""),
			cape(STEVE, EMBER, SHA, 64, 32).replace(",\"url\":\"" + EMBER + "\"", ""),
			"\"" + STEVE + "\":{\"cape\":{\"kind\":\"cape\",\"asset\":null}}",
			"\"" + STEVE + "\":{\"cape\":\"text\"}",
			"\"" + STEVE + "\":[]",
		};
		for (String entry : bad) {
			Fake fake = new Fake(uri -> json("{\"players\":{" + entry + "}}"));
			Lookup l = client(fake).lookup(List.of(STEVE)).join();
			assertEquals(Status.OK, l.status(), entry);
			assertTrue(l.capes().isEmpty(), entry);
		}
	}

	@Test
	void playersFlintDidntAskAboutAreIgnored() {
		Fake fake = new Fake(uri -> json("{\"players\":{" + cape(ALEX, EMBER, SHA, 64, 32) + ","
			+ cape(STEVE, EMBER, SHA, 64, 32).replace(STEVE.toString(), STEVE.toString().toUpperCase()) + "}}"));
		Lookup l = client(fake).lookup(List.of(STEVE)).join();
		assertEquals(List.of(STEVE), List.copyOf(l.capes().keySet()));
	}

	@Test
	void malformedAnswersAreFailuresNotEmptyWardrobes() {
		for (String body : new String[] {"not json", "[]", "{\"players\":[]}", "{\"players\":\"x\"}", "{}", "{\"players\":{"}) {
			Fake fake = new Fake(uri -> json(body));
			assertEquals(Status.FAILED, client(fake).lookup(List.of(STEVE)).join().status(), body);
		}
		Fake notFound = new Fake(uri -> reply(404, "{}", null, null));
		assertEquals(Status.FAILED, client(notFound).lookup(List.of(STEVE)).join().status());
		Fake redirect = new Fake(uri -> reply(308, "", null, null));
		assertEquals(Status.FAILED, client(redirect).lookup(List.of(STEVE)).join().status(), "redirects aren't followed");
	}

	@Test
	void limitsAndOutagesAreBusyAndCarryRetryAfter() {
		Lookup limited = client(new Fake(uri -> reply(429, "{\"error\":\"rate_limited\"}", null, "120"))).lookup(List.of(STEVE)).join();
		assertEquals(Status.BUSY, limited.status());
		assertEquals(120, limited.retryAfterSeconds());
		Lookup down = client(new Fake(uri -> reply(503, "{\"error\":\"unavailable\"}", null, "Wed, 21 Oct 2026 07:28:00 GMT")))
			.lookup(List.of(STEVE)).join();
		assertEquals(Status.BUSY, down.status());
		assertEquals(-1, down.retryAfterSeconds(), "an HTTP date isn't read");
		Lookup offline = client(new Fake(uri -> CompletableFuture.failedFuture(new ConnectException("refused")))).lookup(List.of(STEVE)).join();
		assertEquals(Status.OFFLINE, offline.status());
		Lookup thrown = client(new Fake(uri -> {
			throw new IllegalStateException("transport broke");
		})).lookup(List.of(STEVE)).join();
		assertEquals(Status.FAILED, thrown.status());
	}

	@Test
	void cacheControlIsRead() {
		assertEquals(60, StoreCosmeticsClient.maxAge("public, max-age=60"));
		assertEquals(30, StoreCosmeticsClient.maxAge("max-age=30"));
		assertEquals(0, StoreCosmeticsClient.maxAge("no-store"));
		assertEquals(0, StoreCosmeticsClient.maxAge("private, no-cache"));
		assertEquals(-1, StoreCosmeticsClient.maxAge("public, s-maxage=30"));
		assertEquals(-1, StoreCosmeticsClient.maxAge(null));
		assertEquals(5, StoreCosmeticsClient.retryAfter(" 5 "));
		assertEquals(-1, StoreCosmeticsClient.retryAfter("-5"));
	}

	@Test
	void onlyCapeSizesTheStoreAllows() {
		for (int scale : new int[] {1, 2, 4, 8, 16}) {
			assertTrue(StoreCosmeticsClient.capeSize(64L * scale, 32L * scale));
		}
		assertFalse(StoreCosmeticsClient.capeSize(64 * 3, 32 * 3));
		assertFalse(StoreCosmeticsClient.capeSize(64 * 32, 32 * 32));
		assertFalse(StoreCosmeticsClient.capeSize(32, 16));
	}

	@Test
	void aDownloadFromAnAddressTheStoreDoesntUseIsNeverSent() {
		Fake fake = new Fake(uri -> reply(200, "png", null, null));
		Asset evil = new Asset(ASSET_ID, 1, URI.create("https://evil.example/cape.png"), SHA, 64, 32);
		ExecutionException e = assertThrows(ExecutionException.class, () -> client(fake).download(evil).get());
		assertInstanceOf(StoreCosmeticsClient.Refused.class, e.getCause());
		assertTrue(fake.uris.isEmpty(), "nothing was requested");
	}

	@Test
	void aDownloadReadsAtMostTheAssetLimit() throws Exception {
		Asset ember = new Asset(ASSET_ID, 1, URI.create(EMBER), SHA, 64, 32);
		Fake fake = new Fake(uri -> CompletableFuture.completedFuture(new Response(200, new byte[] {1, 2, 3}, null, null)));
		assertArrayEquals(new byte[] {1, 2, 3}, client(fake).download(ember).get());
		assertEquals(StoreCosmeticsClient.MAX_ASSET_BYTES, fake.caps.get(0));
		Fake missing = new Fake(uri -> reply(404, "", null, null));
		assertInstanceOf(StoreCosmeticsClient.Refused.class, assertThrows(ExecutionException.class, () -> client(missing).download(ember).get())
			.getCause());
		Fake down = new Fake(uri -> reply(503, "", null, null));
		assertInstanceOf(IOException.class, assertThrows(ExecutionException.class, () -> client(down).download(ember).get()).getCause(),
			"a 5xx is worth retrying");
	}

	/** {@code player} wearing an animated cape: a {@code width × height} poster with {@code animation}. */
	static String animated(UUID player, int width, int height, String animation) {
		return cape(player, EMBER, SHA, width, height).replace(",\"url\":\"" + EMBER + "\"", ",\"url\":\"" + EMBER + "\",\"animation\":"
			+ animation);
	}

	@Test
	void anAnimatedCapesManifestComesWithItsPoster() {
		String manifest = CapeAnimationTest.manifest(16, AnimatedCapeFixture.STANDARD).toString();
		Fake fake = new Fake(uri -> json("{\"players\":{" + animated(STEVE, 1024, 512, manifest) + "}}"));
		Asset asset = client(fake).lookup(List.of(STEVE)).join().capes().get(STEVE);
		assertEquals(URI.create(EMBER), asset.url(), "the poster is the cape's texture");
		CapeAnimation animation = asset.animation();
		assertEquals(10, animation.frameCount());
		assertEquals(CapeAnimation.parse(JsonParser.parseString(manifest), StoreEndpoint.defaults()), animation);
		assertNull(new Asset(ASSET_ID, 1, URI.create(EMBER), SHA, 1024, 512).animation(), "a static cape");
		assertNotEquals(new Asset(ASSET_ID, 1, URI.create(EMBER), SHA, 1024, 512), asset, "an animation makes another asset");
	}

	@Test
	void aBrokenManifestDropsOnlyTheAnimation() {
		String manifest = CapeAnimationTest.manifest(16, AnimatedCapeFixture.STANDARD).toString();
		String[] answers = {
			animated(STEVE, 1024, 512, manifest.replace("\"v\":1", "\"v\":2")),
			animated(STEVE, 1024, 512, "\"animated\""),
			animated(STEVE, 1024, 512, "null"),
			// A poster that isn't frame 0 at the animation's scale (at most 16).
			animated(STEVE, 512, 256, manifest),
			animated(STEVE, 64, 32, manifest),
		};
		for (String answer : answers) {
			Fake fake = new Fake(uri -> json("{\"players\":{" + answer + "}}"));
			Asset asset = client(fake).lookup(List.of(STEVE)).join().capes().get(STEVE);
			assertNotNull(asset, answer);
			assertNull(asset.animation(), answer);
		}
		String hd = CapeAnimationTest.manifest(32, AnimatedCapeFixture.HD).toString();
		Fake fake = new Fake(uri -> json("{\"players\":{" + animated(STEVE, 1024, 512, hd) + "}}"));
		assertNotNull(client(fake).lookup(List.of(STEVE)).join().capes().get(STEVE).animation(), "an HD poster is halved to 16 px per unit");
		Fake small = new Fake(uri -> json("{\"players\":{" + animated(STEVE, 256, 128, CapeAnimationTest.manifest(4, 100, 100).toString()) + "}}"));
		assertNotNull(client(small).lookup(List.of(STEVE)).join().capes().get(STEVE).animation());
	}

	@Test
	void aHundredPlayersInLongHdAnimationsFitOneAnswer() {
		int[] durations = new int[120];
		java.util.Arrays.fill(durations, 500);
		String manifest = CapeAnimationTest.manifest(32, durations).toString();
		StringBuilder players = new StringBuilder();
		List<UUID> asked = new ArrayList<>();
		for (int i = 0; i < StoreCosmeticsClient.MAX_BATCH; i++) {
			UUID id = new UUID(0x1234_5678_9abc_4defL, 0x8000_0000_0000_0000L | i);
			asked.add(id);
			players.append(i == 0 ? "" : ",").append(animated(id, 1024, 512, manifest));
		}
		String body = "{\"players\":{" + players + "}}";
		int size = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
		assertTrue(size > 256 * 1024, "far over the old cap: " + size);
		assertTrue(size < StoreCosmeticsClient.MAX_LOOKUP_BYTES / 2, "with room to spare under the new one: " + size);
		Fake fake = new Fake(uri -> json(body));
		Lookup l = client(fake).lookup(asked).join();
		assertEquals(Status.OK, l.status());
		assertEquals(100, l.capes().size());
		assertEquals(40, l.capes().get(asked.get(99)).animation().strips().size());
	}

	@Test
	void aStripIsReadUpToTheSizeTheStoreGave() throws Exception {
		CapeAnimation animation = CapeAnimation.parse(CapeAnimationTest.manifest(16, AnimatedCapeFixture.STANDARD), StoreEndpoint.defaults());
		Fake fake = new Fake(uri -> CompletableFuture.completedFuture(new Response(200, new byte[] {1, 2, 3}, null, null)));
		assertArrayEquals(new byte[] {1, 2, 3}, client(fake).download(animation.strips().get(0)).get());
		assertEquals(100_000, fake.caps.get(0), "the strip's own size");
		assertEquals(animation.strips().get(0).url(), fake.uris.get(0));
		assertEquals(2 * 1024 * 1024, StoreCosmeticsClient.MAX_ASSET_BYTES, "a poster may take the Store's 2 MiB");
	}
}
