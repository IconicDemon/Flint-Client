package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoreEndpointTest {
	private static final StoreEndpoint STORE = StoreEndpoint.defaults();
	private static final String SUPABASE = "https://abcdefghijklmnopqrst.supabase.co/storage/v1/object/public/cosmetic-assets/";

	@Test
	void theDefaultIsTheStoreItself() {
		assertEquals("https://store.flintclient.com", STORE.origin());
		assertEquals("store.flintclient.com", STORE.host());
	}

	@Test
	void aBaseIsAnHttpsOriginOrHttpOnLoopbackOnly() {
		assertNotNull(StoreEndpoint.parse("https://store.flintclient.com/", "t"));
		assertNotNull(StoreEndpoint.parse("https://flint-store-website-preview.example.workers.dev", "t"));
		assertNotNull(StoreEndpoint.parse("http://127.0.0.1:8787", "t"));
		assertNotNull(StoreEndpoint.parse("http://localhost:3000/", "t"));
		assertNull(StoreEndpoint.parse("http://store.flintclient.com", "t"), "no plain http off this computer");
		assertNull(StoreEndpoint.parse("https://store.flintclient.com/api", "t"), "an origin, not a path");
		assertNull(StoreEndpoint.parse("https://store.flintclient.com/?x=1", "t"));
		assertNull(StoreEndpoint.parse("https://user:pw@store.flintclient.com", "t"));
		assertNull(StoreEndpoint.parse("https://store.flintclient.com/#x", "t"));
		assertNull(StoreEndpoint.parse("ftp://store.flintclient.com", "t"));
		assertNull(StoreEndpoint.parse("file:///etc/passwd", "t"));
		assertNull(StoreEndpoint.parse("javascript:alert(1)", "t"));
		assertNull(StoreEndpoint.parse("https://", "t"));
		assertNull(StoreEndpoint.parse("", "t"));
		assertNull(StoreEndpoint.parse(null, "t"));
	}

	@Test
	void theLookupAsksForEveryPlayerInOneQuery() {
		UUID a = UUID.fromString("069A79F4-44E9-4726-A5BE-FCA90E38AAF5");
		UUID b = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
		assertEquals("https://store.flintclient.com/api/v1/players/cosmetics/?uuids=069a79f4-44e9-4726-a5be-fca90e38aaf5,"
			+ "853c80ef-3c37-49fd-aa49-938b674adae6", STORE.cosmeticsLookup(List.of(a, b)).toString());
		StoreEndpoint local = StoreEndpoint.parse("http://127.0.0.1:8787/", "t");
		assertEquals("http://127.0.0.1:8787/api/v1/players/cosmetics/?uuids=069a79f4-44e9-4726-a5be-fca90e38aaf5",
			local.cosmeticsLookup(List.of(a)).toString());
	}

	@Test
	void assetsComeFromTheStoreOrItsSupabaseBucketOnly() {
		assertTrue(STORE.allowsAsset(URI.create("https://store.flintclient.com/test-capes/ember-cape.png")));
		assertTrue(STORE.allowsAsset(URI.create("https://STORE.flintclient.com:443/test-capes/ember-cape.png")));
		assertTrue(STORE.allowsAsset(URI.create(SUPABASE + "staff/123/cape.png")));
		assertFalse(STORE.allowsAsset(URI.create("https://evil.example/cape.png")));
		assertFalse(STORE.allowsAsset(URI.create("https://store.flintclient.com.evil.example/cape.png")));
		assertFalse(STORE.allowsAsset(URI.create("http://store.flintclient.com/test-capes/ember-cape.png")), "never downgraded to http");
		assertFalse(STORE.allowsAsset(URI.create("https://store.flintclient.com:8443/cape.png")), "another port is another origin");
		assertFalse(STORE.allowsAsset(URI.create("https://user@store.flintclient.com/cape.png")));
		assertFalse(STORE.allowsAsset(URI.create("https://store.flintclient.com/cape.png?x=1")));
		assertFalse(STORE.allowsAsset(URI.create("https://store.flintclient.com/a/../cape.png")));
		assertFalse(STORE.allowsAsset(URI.create("https://store.flintclient.com/a/%2e%2e/cape.png")));
		assertFalse(STORE.allowsAsset(URI.create("https://store.flintclient.com/a//cape.png")));
		assertFalse(STORE.allowsAsset(URI.create("https://abcdefghijklmnopqrst.supabase.co/storage/v1/object/public/other-bucket/x.png")));
		assertFalse(STORE.allowsAsset(URI.create("https://abcdefghijklmnopqrst.supabase.co/storage/v1/object/sign/cosmetic-assets/x.png")));
		assertFalse(STORE.allowsAsset(URI.create("http://abcdefghijklmnopqrst.supabase.co/storage/v1/object/public/cosmetic-assets/x.png")));
		assertFalse(STORE.allowsAsset(URI.create("https://evil.supabase.co.example/storage/v1/object/public/cosmetic-assets/x.png")));
		assertFalse(STORE.allowsAsset(URI.create(SUPABASE)), "the bucket itself isn't a file");
		assertFalse(STORE.allowsAsset(URI.create("file:///C:/cape.png")));
		assertFalse(STORE.allowsAsset(null));
		StoreEndpoint local = StoreEndpoint.parse("http://127.0.0.1:8787", "t");
		assertTrue(local.allowsAsset(URI.create("http://127.0.0.1:8787/test-capes/ember-cape.png")));
		assertFalse(local.allowsAsset(URI.create("http://127.0.0.1:9999/test-capes/ember-cape.png")));
		assertFalse(local.allowsAsset(URI.create("http://192.168.1.1/cape.png")));
	}

	@Test
	void onlyLocalhostAndLoopbackMayBePlainHttp() {
		assertNull(StoreEndpoint.parse("http://[::1]:8787", "t"));
		assertNull(StoreEndpoint.parse("http://192.168.1.10:8787", "t"));
		assertTrue(StoreEndpoint.parse("http://127.0.0.1:8787", "t").loopback());
		assertFalse(STORE.loopback());
		assertEquals("https://store.flintclient.com/api/v1/players/cosmetics/", STORE.publicCosmeticsUrl());
	}
}
