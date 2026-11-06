package com.flintclient.client.module.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The game's end of the game link against a stand-in for Flint's listener (the same wire format as gamelink.rs). */
class LauncherLinkTest {
	private static final String SECRET = "ab".repeat(32);

	private ServerSocket server;
	private final List<JsonObject> requests = Collections.synchronizedList(new ArrayList<>());

	@AfterEach
	void close() throws Exception {
		if (server != null) {
			server.close();
		}
	}

	/** A listener answering each request line with {@code answer(request)}. */
	private LauncherLink serve(Function<JsonObject, String> answer) throws Exception {
		server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
		Thread thread = new Thread(() -> {
			while (!server.isClosed()) {
				try (Socket socket = server.accept()) {
					String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
					JsonObject request = JsonParser.parseString(line).getAsJsonObject();
					requests.add(request);
					OutputStream out = socket.getOutputStream();
					out.write((answer.apply(request) + "\n").getBytes(StandardCharsets.UTF_8));
					out.flush();
				} catch (Exception e) {
					return;
				}
			}
		});
		thread.setDaemon(true);
		thread.start();
		return new LauncherLink(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), SECRET);
	}

	@Test
	void onlyALoopbackLinkWithAWellFormedSecretIsUsed() {
		assertNotNull(LauncherLink.fromEnvironment(Map.of(LauncherLink.ENV_ADDRESS, "127.0.0.1:4567", LauncherLink.ENV_SECRET, SECRET)));
		assertNull(LauncherLink.fromEnvironment(Map.of()));
		assertNull(LauncherLink.fromEnvironment(Map.of(LauncherLink.ENV_ADDRESS, "127.0.0.1:4567")));
		assertNull(LauncherLink.fromEnvironment(Map.of(LauncherLink.ENV_ADDRESS, "10.0.0.5:4567", LauncherLink.ENV_SECRET, SECRET)));
		assertNull(LauncherLink.fromEnvironment(Map.of(LauncherLink.ENV_ADDRESS, "example.com:4567", LauncherLink.ENV_SECRET, SECRET)));
		assertNull(LauncherLink.fromEnvironment(Map.of(LauncherLink.ENV_ADDRESS, "127.0.0.1:99999", LauncherLink.ENV_SECRET, SECRET)));
		assertNull(LauncherLink.fromEnvironment(Map.of(LauncherLink.ENV_ADDRESS, "127.0.0.1:4567", LauncherLink.ENV_SECRET, "short")));
		assertNull(LauncherLink.fromEnvironment(Map.of(LauncherLink.ENV_ADDRESS, "127.0.0.1:4567", LauncherLink.ENV_SECRET, "AB".repeat(32))));
	}

	@Test
	void everyRequestCarriesTheVersionAndTheSecret() throws Exception {
		LauncherLink link = serve(request -> "{\"ok\":true,\"active\":\"a\",\"accounts\":[],\"signIn\":{\"state\":\"idle\"}}");
		link.accounts();
		JsonObject request = requests.get(0);
		assertEquals(LauncherLink.PROTOCOL, request.get("v").getAsInt());
		assertEquals(SECRET, request.get("secret").getAsString());
		assertEquals("accounts", request.get("op").getAsString());
	}

	@Test
	void accountsAndTheSignInStateAreRead() throws Exception {
		LauncherLink link = serve(request -> "{\"ok\":true,\"active\":\"a\",\"accounts\":[{\"uuid\":\"a\",\"username\":\"Alpha\",\"offline\":false},"
			+ "{\"uuid\":\"b\",\"username\":\"Beta\",\"offline\":true},{\"username\":\"broken\"}],"
			+ "\"signIn\":{\"state\":\"done\",\"uuid\":\"b\",\"username\":\"Beta\"}}");
		LauncherLink.Accounts accounts = link.accounts();
		assertEquals("a", accounts.active());
		assertEquals(List.of(new LauncherLink.Account("a", "Alpha", false), new LauncherLink.Account("b", "Beta", true)), accounts.list());
		assertTrue(accounts.signIn().done());
		assertEquals("b", accounts.signIn().uuid());
	}

	@Test
	void aSessionMustBeForTheAccountAskedFor() throws Exception {
		LauncherLink link = serve(request -> {
			String uuid = request.get("uuid").getAsString();
			return "{\"ok\":true,\"uuid\":\"" + (uuid.equals("odd") ? "other" : uuid) + "\",\"username\":\"Alpha\",\"accessToken\":\"tok\",\"offline\":false}";
		});
		LauncherLink.Session session = link.session("a");
		assertEquals("tok", session.accessToken());
		assertFalse(session.toString().contains("tok"), "a session never prints its token");
		assertThrows(LauncherLink.LinkException.class, () -> link.session("odd"));
	}

	@Test
	void flintsRefusalsAndAStaleSecretAreToldApart() throws Exception {
		LauncherLink link = serve(request -> switch (request.get("op").getAsString()) {
			case "activate" -> "{\"ok\":false,\"error\":\"That account isn't signed in to Flint any more.\"}";
			case "addAccount" -> request.has("opens") && request.get("opens").getAsBoolean()
				? "{\"ok\":true,\"started\":true,\"url\":\"https://login.microsoftonline.com/x\"}" : "{\"ok\":true,\"started\":true}";
			case "removeAccount" -> request.get("uuid").getAsString().equals(request.get("playing").getAsString())
				? "{\"ok\":false,\"error\":\"You're playing as this account.\"}" : "{\"ok\":true}";
			default -> "{\"ok\":false,\"error\":\"unauthorized\"}";
		});
		LauncherLink.LinkException refused = assertThrows(LauncherLink.LinkException.class, () -> link.activate("zz"));
		assertFalse(refused.unreachable());
		assertEquals("That account isn't signed in to Flint any more.", refused.getMessage());
		assertEquals("https://login.microsoftonline.com/x", link.addAccount(), "the game opens the page itself, so it says so");
		link.removeAccount("b", "a");
		assertFalse(assertThrows(LauncherLink.LinkException.class, () -> link.removeAccount("a", "a")).unreachable());
		// A Flint restarted since this game started doesn't know its secret: as good as no Flint.
		assertTrue(assertThrows(LauncherLink.LinkException.class, link::accounts).unreachable());
	}

	@Test
	void noFlintMeansUnreachable() throws Exception {
		int port;
		try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = probe.getLocalPort();
		}
		LauncherLink link = new LauncherLink(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), SECRET);
		assertTrue(assertThrows(LauncherLink.LinkException.class, link::accounts).unreachable());
	}

	@Test
	void theGameOpensOnlyMicrosoftsSignInPage() {
		assertTrue(AccountSwitcher.signInPage("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?client_id=x&state=y"));
		assertTrue(AccountSwitcher.signInPage("https://login.live.com/oauth20_authorize.srf?x"));
		assertFalse(AccountSwitcher.signInPage(null));
		assertFalse(AccountSwitcher.signInPage("http://login.microsoftonline.com/x"), "never without TLS");
		assertFalse(AccountSwitcher.signInPage("https://login.microsoftonline.com.example.net/x"), "a look-alike host");
		assertFalse(AccountSwitcher.signInPage("file:///C:/Windows/System32/calc.exe"));
		assertFalse(AccountSwitcher.signInPage("https://login.live.com/x y"));
	}

	@Test
	void uuidsAreReadInEitherForm() {
		UUID id = UUID.fromString("0123abcd-4567-89ef-0123-456789abcdef");
		assertEquals(id, SessionSwitch.uuid("0123abcd456789ef0123456789abcdef"));
		assertEquals(id, SessionSwitch.uuid("0123abcd-4567-89ef-0123-456789abcdef"));
		assertThrows(IllegalArgumentException.class, () -> SessionSwitch.uuid("1234"));
	}
}
