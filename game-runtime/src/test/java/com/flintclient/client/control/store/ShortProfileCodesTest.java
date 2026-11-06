package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.store.ProfileShareClient.Reply;
import com.flintclient.client.control.store.ProfileShareClient.Result;
import com.flintclient.client.control.store.ProfileShareClient.Status;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Short profile codes: their format, the services file, and the requests to Flint's servers (a stand-in server here). */
class ShortProfileCodesTest {
	private static final FlintServices SERVICES = new FlintServices("https://example.supabase.co", "sb_publishable_test");

	@TempDir
	Path config;

	private LayoutStore store() {
		return new LayoutStore(config, new ControlSettings(config, JsonStoreTest.CLOCK), JsonStoreTest.CLOCK);
	}

	/** A transport that records what was sent and answers with {@code answer}. */
	private static final class FakeTransport implements ProfileShareClient.Transport {
		final List<URI> uris = new ArrayList<>();
		final List<Map<String, String>> headers = new ArrayList<>();
		final List<String> bodies = new ArrayList<>();
		final Function<String, CompletableFuture<Reply>> answer;

		FakeTransport(Function<String, CompletableFuture<Reply>> answer) {
			this.answer = answer;
		}

		static FakeTransport replying(int status, String body) {
			return new FakeTransport(sent -> CompletableFuture.completedFuture(new Reply(status, body)));
		}

		static FakeTransport failing(Throwable error) {
			return new FakeTransport(sent -> CompletableFuture.failedFuture(error));
		}

		@Override
		public CompletableFuture<Reply> post(URI uri, Map<String, String> sentHeaders, String body) {
			uris.add(uri);
			headers.add(sentHeaders);
			bodies.add(body);
			return answer.apply(body);
		}
	}

	/** A stand-in for the two database functions: codes kept in a map, as the real ones keep them in a table. */
	private static final class FakeServer implements ProfileShareClient.Transport {
		final Map<String, String> shares = new HashMap<>();
		private int next;

		@Override
		public CompletableFuture<Reply> post(URI uri, Map<String, String> headers, String body) {
			JsonObject args = JsonParser.parseString(body).getAsJsonObject();
			if (uri.getPath().endsWith("/create_profile_share")) {
				String code = "K7QX3" + ShortCodes.ALPHABET.charAt(next++);
				shares.put(code, args.get("p_payload").toString());
				return CompletableFuture.completedFuture(new Reply(200, "{\"ok\":true,\"code\":\"" + code + "\",\"expires_at\":\"2026-11-26T10:00:00Z\"}"));
			}
			String payload = shares.get(args.get("p_code").getAsString());
			return CompletableFuture.completedFuture(new Reply(200, payload == null ? "null" : payload));
		}
	}

	private static Result done(CompletableFuture<Result> future) {
		try {
			return future.get();
		} catch (InterruptedException | ExecutionException e) {
			throw new AssertionError(e);
		}
	}

	// ---------- the code format ----------

	@Test
	void sixCharactersFromTheUnambiguousAlphabetAreACode() {
		assertEquals("K7QX3M", ShortCodes.parse("K7QX3M"));
		assertEquals("K7QX3M", ShortCodes.parse("k7qx3m"), "case doesn't matter");
		assertEquals("K7QX3M", ShortCodes.parse("  K7Q X3M\n"), "spaces from a chat are ignored");
		assertEquals("K7QX3M", ShortCodes.parse("FLINT-K7QX3M"));
		for (String text : new String[] {null, "", "K7QX3", "K7QX3MM", "K7QX3-", "K7QX30", "K7QX3O", "K7QX31", "K7QX3I", "K7QX3L",
			"FLINT-AAAAAAAA", "K7QX3Ü", "x".repeat(100)}) {
			assertNull(ShortCodes.parse(text), String.valueOf(text));
		}
		assertEquals(31, ShortCodes.ALPHABET.length());
		for (char ambiguous : "0O1IL".toCharArray()) {
			assertTrue(ShortCodes.ALPHABET.indexOf(ambiguous) < 0, String.valueOf(ambiguous));
		}
	}

	@Test
	void aLongCodeIsNeverTakenForAShortOne() {
		LayoutStore store = store();
		String longCode = store.shareCode(store.activeId());
		assertNotNull(longCode);
		assertFalse(ShortCodes.isShortCode(longCode));
		assertFalse(ShortCodes.looksMistyped(longCode));
		assertTrue(store.importCode(longCode).succeeded(), "long codes keep importing");
	}

	@Test
	void sixCharactersWithALookAlikeAreCalledMistyped() {
		assertTrue(ShortCodes.looksMistyped("K7QX3O"));
		assertTrue(ShortCodes.looksMistyped("k7qx31"));
		assertFalse(ShortCodes.looksMistyped("K7QX3M"));
		assertFalse(ShortCodes.looksMistyped("K7QX3"));
		assertFalse(ShortCodes.looksMistyped("{\"a\":"));
		LayoutResult result = store().importCode("K7QX3O");
		assertFalse(result.succeeded());
		assertTrue(result.error().contains("0, O, 1, I or L"), result.error());
	}

	@Test
	void aShortCodeIsNotReadAsAProfileOnItsOwn() {
		LayoutStore store = store();
		assertFalse(store.importCode("K7QX3M").succeeded());
		assertEquals(1, store.list().size());
	}

	// ---------- the services file ----------

	@Test
	void theServicesFileNamesTheProjectOrSaysThereIsNone() {
		FlintServices s = FlintServices.parse("{\"configured\":true,\"supabaseUrl\":\"https://abc.supabase.co/\",\"supabaseAnonKey\":\"sb_publishable_x\"}");
		assertNotNull(s);
		assertEquals("https://abc.supabase.co", s.baseUrl());
		assertEquals(URI.create("https://abc.supabase.co/rest/v1/rpc/get_profile_share"), s.rpc("get_profile_share"));
		assertFalse(s.toString().contains("sb_publishable_x"), "the key is never printed");
		assertNull(FlintServices.parse("{\"configured\":false}"));
		assertNull(FlintServices.parse("{\"supabaseUrl\":\"https://abc.supabase.co\",\"supabaseAnonKey\":\"k\"}"), "configured must say true");
		assertNull(FlintServices.parse("not json"));
		assertNull(FlintServices.parse(null));
		for (String url : new String[] {"http://abc.supabase.co", "ftp://abc", "https://abc.supabase.co?x=1", "https://user@abc.supabase.co", "abc.supabase.co", ""}) {
			assertNull(FlintServices.parse("{\"configured\":true,\"supabaseUrl\":\"" + url + "\",\"supabaseAnonKey\":\"k\"}"), url);
		}
		assertNotNull(FlintServices.parse("{\"configured\":true,\"supabaseUrl\":\"http://127.0.0.1:54321\",\"supabaseAnonKey\":\"k\"}"), "a local test server");
		for (String key : new String[] {"", "sb_secret_abc", "has space", "line\nbreak"}) {
			assertNull(FlintServices.parse("{\"configured\":true,\"supabaseUrl\":\"https://abc.supabase.co\",\"supabaseAnonKey\":"
				+ new com.google.gson.JsonPrimitive(key) + "}"), key);
		}
	}

	@Test
	void aBuildWithoutServicesSharesLongCodesOnly() {
		FakeTransport transport = FakeTransport.replying(200, "{}");
		ProfileShareClient client = new ProfileShareClient(null, transport);
		assertFalse(client.configured());
		assertEquals(Status.NOT_CONFIGURED, done(client.create("{\"modules\":{}}")).status());
		assertEquals(Status.NOT_CONFIGURED, done(client.fetch("K7QX3M")).status());
		assertTrue(transport.bodies.isEmpty(), "nothing is sent");
	}

	// ---------- requests and answers ----------

	@Test
	void shareSendsTheProfileToCreateProfileShareWithThePublishableKey() {
		LayoutStore store = store();
		String payload = store.sharePayload(store.activeId());
		assertNotNull(payload);
		FakeTransport transport = FakeTransport.replying(200, "{\"ok\":true,\"code\":\"K7QX3M\",\"expires_at\":\"2026-11-26T10:00:00Z\"}");
		Result result = done(new ProfileShareClient(SERVICES, transport).create(payload));
		assertEquals(new Result(Status.OK, "K7QX3M"), result);
		assertEquals(URI.create("https://example.supabase.co/rest/v1/rpc/create_profile_share"), transport.uris.get(0));
		Map<String, String> headers = transport.headers.get(0);
		assertEquals("sb_publishable_test", headers.get("apikey"));
		assertEquals("Bearer sb_publishable_test", headers.get("Authorization"));
		assertEquals("application/json", headers.get("Content-Type"));
		JsonObject sent = JsonParser.parseString(transport.bodies.get(0)).getAsJsonObject();
		assertEquals(JsonParser.parseString(payload), sent.get("p_payload"), "the profile goes as JSON, not as a string");
		assertEquals(1, sent.size());
	}

	@Test
	void thePayloadIsWhatALongCodeHolds() {
		LayoutStore store = store();
		store.active().module(ModuleId.FPS).setEnabled(true);
		String payload = store.sharePayload(store.activeId());
		String fromLongCode = ShareCodes.decode(store.shareCode(store.activeId()));
		assertEquals(JsonParser.parseString(fromLongCode), JsonParser.parseString(payload));
	}

	@Test
	void createAnswersAreCheckedAndMapped() {
		Object[][] cases = {
			{200, "{\"ok\":false,\"error\":\"rate_limited\"}", Status.BUSY},
			{200, "{\"ok\":false,\"error\":\"busy\"}", Status.BUSY},
			{200, "{\"ok\":false,\"error\":\"invalid\"}", Status.REFUSED},
			{200, "{\"ok\":false,\"error\":\"too_large\"}", Status.REFUSED},
			{200, "{\"ok\":false,\"error\":\"something new\"}", Status.FAILED},
			{200, "{\"ok\":true,\"code\":\"K7QX30\"}", Status.FAILED},
			{200, "{\"ok\":true,\"code\":\"<script>\"}", Status.FAILED},
			{200, "{\"ok\":true}", Status.FAILED},
			{200, "[1,2]", Status.FAILED},
			{200, "not json", Status.FAILED},
			{200, "", Status.FAILED},
			{404, "{\"code\":\"PGRST202\"}", Status.FAILED},
			{401, "{\"message\":\"Invalid API key\"}", Status.FAILED},
			{429, "", Status.BUSY},
			{503, "", Status.BUSY},
			{500, "", Status.FAILED},
		};
		for (Object[] c : cases) {
			Result result = done(new ProfileShareClient(SERVICES, FakeTransport.replying((Integer) c[0], (String) c[1])).create("{\"modules\":{}}"));
			assertEquals(c[2], result.status(), c[0] + " " + c[1]);
			assertFalse(result.ok());
		}
		Result lower = done(new ProfileShareClient(SERVICES, FakeTransport.replying(200, "{\"ok\":true,\"code\":\"k7qx3m\"}")).create("{\"modules\":{}}"));
		assertEquals("K7QX3M", lower.value());
	}

	@Test
	void noConnectionOrATimeoutIsOfflineAnythingElseAFailure() {
		for (Throwable error : new Throwable[] {new ConnectException("refused"), new HttpTimeoutException("slow"), new IOException("reset"),
			new java.util.concurrent.CompletionException(new ConnectException("wrapped"))}) {
			ProfileShareClient client = new ProfileShareClient(SERVICES, FakeTransport.failing(error));
			assertEquals(Status.OFFLINE, done(client.create("{\"modules\":{}}")).status(), error.toString());
			assertEquals(Status.OFFLINE, done(client.fetch("K7QX3M")).status(), error.toString());
		}
		ProfileShareClient broken = new ProfileShareClient(SERVICES, FakeTransport.failing(new IllegalStateException("answer larger than 131072 bytes")));
		assertEquals(Status.FAILED, done(broken.fetch("K7QX3M")).status());
		ProfileShareClient throwing = new ProfileShareClient(SERVICES, (uri, headers, body) -> {
			throw new IllegalArgumentException("bad uri");
		});
		assertEquals(Status.FAILED, done(throwing.create("{\"modules\":{}}")).status());
	}

	@Test
	void aProfileTooBigOrNotAnObjectIsNeverSent() {
		FakeTransport transport = FakeTransport.replying(200, "{\"ok\":true,\"code\":\"K7QX3M\"}");
		ProfileShareClient client = new ProfileShareClient(SERVICES, transport);
		String big = "{\"modules\":{},\"x\":\"" + "a".repeat(ProfileShareClient.MAX_PAYLOAD_BYTES) + "\"}";
		assertEquals(Status.REFUSED, done(client.create(big)).status());
		assertEquals(Status.REFUSED, done(client.create("[1]")).status());
		assertEquals(Status.REFUSED, done(client.create("{broken")).status());
		assertTrue(transport.bodies.isEmpty());
	}

	@Test
	void fetchSendsTheCodeUpperCaseAndReadsTheProfile() {
		FakeTransport transport = FakeTransport.replying(200, "{\"schema\":1,\"name\":\"PvP\",\"modules\":{}}");
		Result result = done(new ProfileShareClient(SERVICES, transport).fetch(" k7qx3m "));
		assertTrue(result.ok());
		assertEquals(JsonParser.parseString("{\"schema\":1,\"name\":\"PvP\",\"modules\":{}}"), JsonParser.parseString(result.value()));
		assertEquals(URI.create("https://example.supabase.co/rest/v1/rpc/get_profile_share"), transport.uris.get(0));
		assertEquals("{\"p_code\":\"K7QX3M\"}", transport.bodies.get(0));
	}

	@Test
	void fetchAnswersAreCheckedAndMapped() {
		Object[][] cases = {
			{200, "null", Status.NOT_FOUND},
			{200, " null\n", Status.NOT_FOUND},
			{200, "[]", Status.FAILED},
			{200, "\"text\"", Status.FAILED},
			{200, "{broken", Status.FAILED},
			{400, "{\"code\":\"P0001\",\"message\":\"rate_limited\"}", Status.BUSY},
			{400, "{\"code\":\"22P02\"}", Status.FAILED},
			{429, "", Status.BUSY},
			{404, "", Status.FAILED},
		};
		for (Object[] c : cases) {
			Result result = done(new ProfileShareClient(SERVICES, FakeTransport.replying((Integer) c[0], (String) c[1])).fetch("K7QX3M"));
			assertEquals(c[2], result.status(), c[0] + " " + c[1]);
		}
		FakeTransport transport = FakeTransport.replying(200, "{}");
		assertEquals(Status.NOT_FOUND, done(new ProfileShareClient(SERVICES, transport).fetch("FLINT-ABCDEFGHIJ")).status());
		assertEquals(Status.NOT_FOUND, done(new ProfileShareClient(SERVICES, transport).fetch("K7QX3O")).status());
		assertTrue(transport.bodies.isEmpty(), "a text that isn't a code is never sent");
	}

	@Test
	void aFetchedProfileIsReadExactlyAsALongCodesProfile() {
		LayoutStore mine = store();
		Layout pvp = mine.active();
		pvp.setName("PvP");
		pvp.module(ModuleId.FPS).setEnabled(true);
		pvp.module(ModuleId.FPS).setPlacement(HudPlacement.of(Anchor.TOP_RIGHT, 12, 30));
		pvp.module(PlayerMods.Zoom.ID).set(PlayerMods.Zoom.LEVEL, 9);
		FakeServer server = new FakeServer();
		ProfileShareClient client = new ProfileShareClient(SERVICES, server);
		Result shared = done(client.create(mine.sharePayload(mine.activeId())));
		assertTrue(shared.ok());
		assertTrue(ShortCodes.isShortCode(shared.value()));
		assertEquals(Status.OK, done(client.create(mine.sharePayload(mine.activeId()))).status());
		assertEquals(2, server.shares.size(), "a fresh code each time");

		LayoutStore theirs = store();
		Result fetched = done(client.fetch(shared.value().toLowerCase(java.util.Locale.ROOT)));
		assertTrue(fetched.ok());
		LayoutResult made = theirs.importText(fetched.value());
		assertTrue(made.succeeded(), String.valueOf(made.error()));
		Layout copy = theirs.get(made.id());
		assertNotNull(copy);
		assertEquals("PvP", copy.name());
		assertEquals(HudPlacement.of(Anchor.TOP_RIGHT, 12, 30), copy.module(ModuleId.FPS).placement());
		assertEquals(9, copy.module(PlayerMods.Zoom.ID).integer(PlayerMods.Zoom.LEVEL));
		for (ModuleState module : pvp.modules()) {
			assertEquals(module.enabled(), copy.module(module.id()).enabled(), module.id());
		}
		assertEquals(Status.NOT_FOUND, done(client.fetch("222222")).status());
	}

	@Test
	void aFetchedProfileThatIsNotAProfileMakesNothing() {
		LayoutStore store = store();
		for (String answer : new String[] {"{\"name\":\"x\"}", "{\"modules\":3}", "{\"schema\":99,\"name\":\"Future\",\"modules\":{}}"}) {
			Result fetched = done(new ProfileShareClient(SERVICES, FakeTransport.replying(200, answer)).fetch("K7QX3M"));
			assertTrue(fetched.ok());
			assertFalse(store.importText(fetched.value()).succeeded(), answer);
		}
		assertEquals(1, store.list().size());
	}

	@Test
	void aProfileWithEveryModOnFitsInAShortCode() {
		LayoutStore store = store();
		for (ModuleState module : store.active().modules()) {
			module.setEnabled(true);
			module.setPlacement(HudPlacement.of(Anchor.BOTTOM_LEFT, 7, 9));
		}
		String payload = store.sharePayload(store.activeId());
		int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
		assertTrue(bytes < ProfileShareClient.MAX_PAYLOAD_BYTES / 2, "every mod on and moved: " + bytes + " bytes");
	}

	@Test
	void theRealTransportTalksToAServerOverHttp() throws IOException {
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		Map<String, String> seen = new java.util.concurrent.ConcurrentHashMap<>();
		server.createContext("/rest/v1/rpc/create_profile_share", exchange -> {
			seen.put("method", exchange.getRequestMethod());
			seen.put("apikey", String.valueOf(exchange.getRequestHeaders().getFirst("apikey")));
			seen.put("authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
			seen.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] answer = "{\"ok\":true,\"code\":\"K7QX3M\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, answer.length);
			exchange.getResponseBody().write(answer);
			exchange.close();
		});
		server.createContext("/rest/v1/rpc/get_profile_share", exchange -> {
			exchange.getRequestBody().readAllBytes();
			// Far past the cap: the download stops and it counts as a failure.
			byte[] answer = ("{\"modules\":{},\"x\":\"" + "a".repeat(ProfileShareClient.MAX_ANSWER_BYTES) + "\"}").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, answer.length);
			try {
				exchange.getResponseBody().write(answer);
			} catch (IOException ignored) {
				// The client hung up.
			}
			exchange.close();
		});
		server.start();
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			ProfileShareClient client = new ProfileShareClient(new FlintServices(base, "sb_publishable_test"), ProfileShareClient.http());
			assertEquals(new Result(Status.OK, "K7QX3M"), done(client.create("{\"modules\":{}}")));
			assertEquals("POST", seen.get("method"));
			assertEquals("sb_publishable_test", seen.get("apikey"));
			assertEquals("Bearer sb_publishable_test", seen.get("authorization"));
			assertEquals("{\"p_payload\":{\"modules\":{}}}", seen.get("body"));
			assertEquals(Status.FAILED, done(client.fetch("K7QX3M")).status());
		} finally {
			server.stop(0);
		}
		// Nothing listening there any more: offline.
		int port = server.getAddress().getPort();
		ProfileShareClient gone = new ProfileShareClient(new FlintServices("http://127.0.0.1:" + port, "k"), ProfileShareClient.http());
		assertEquals(Status.OFFLINE, done(gone.fetch("K7QX3M")).status());
	}

	@Test
	void anAnswerPastTheCapStopsTheDownload() {
		ProfileShareClient.CappedBody body = new ProfileShareClient.CappedBody(10);
		body.onSubscribe(new java.util.concurrent.Flow.Subscription() {
			@Override
			public void request(long n) {
			}

			@Override
			public void cancel() {
			}
		});
		body.onNext(List.of(ByteBuffer.wrap("12345".getBytes(StandardCharsets.UTF_8))));
		body.onNext(List.of(ByteBuffer.wrap("678901".getBytes(StandardCharsets.UTF_8))));
		assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
		ProfileShareClient.CappedBody small = new ProfileShareClient.CappedBody(10);
		small.onNext(List.of(ByteBuffer.wrap("null".getBytes(StandardCharsets.UTF_8))));
		small.onComplete();
		assertEquals("null", small.getBody().toCompletableFuture().join());
	}
}
