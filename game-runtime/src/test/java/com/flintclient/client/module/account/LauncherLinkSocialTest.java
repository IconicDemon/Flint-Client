package com.flintclient.client.module.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.module.social.SocialLink;
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
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Friends and the Store over the game link, against a stand-in for Flint's listener (the wire format of gamelink.rs). */
class LauncherLinkSocialTest {
	private static final String SECRET = "cd".repeat(32);
	private static final String STEVE = "8667ba71b85a4004af54457a9734eed7";
	private static final String ALEX = "ec561538f3fd461daff5086b22154bce";

	private ServerSocket server;
	private final List<JsonObject> requests = Collections.synchronizedList(new ArrayList<>());

	@AfterEach
	void close() throws Exception {
		if (server != null) {
			server.close();
		}
	}

	private SocialLink serve(Function<JsonObject, String> answer) throws Exception {
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
		return new SocialLink(new LauncherLink(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), SECRET));
	}

	@Test
	void friendsAreAskedForWithTheAccountAndTheOpenChat() throws Exception {
		SocialLink link = serve(r -> "{\"ok\":true,\"phase\":\"ready\",\"friends\":[{\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\",\"status\":\"away\"}]}");
		SocialLink.Friends f = link.friends(STEVE, ALEX);
		assertEquals("away", f.friends().get(0).status());
		JsonObject sent = requests.get(0);
		assertEquals(1, sent.get("v").getAsInt());
		assertEquals(SECRET, sent.get("secret").getAsString());
		assertEquals("friends", sent.get("op").getAsString());
		assertEquals(STEVE, sent.get("playing").getAsString());
		assertEquals(ALEX, sent.get("chat").getAsString());
		link.friends(STEVE, null);
		assertFalse(requests.get(1).has("chat"));
		assertFalse(requests.get(1).has("since"), "asked at once");
	}

	@Test
	void aHeldPollSendsTheRevisionAndCanBeCutShort() throws Exception {
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		SocialLink link = serve(r -> {
			if (r.has("since")) {
				// Flint holds it: nothing changes until the test lets go.
				try {
					release.await(10, java.util.concurrent.TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			return "{\"ok\":true,\"phase\":\"ready\",\"revision\":42}";
		});
		SocialLink.Held now = link.friends(STEVE, ALEX, -1, 20_000, null);
		assertEquals(42, now.revision());
		assertEquals("ready", now.friends().phase());
		LauncherLink.Call call = new LauncherLink.Call();
		java.util.concurrent.atomic.AtomicReference<Exception> thrown = new java.util.concurrent.atomic.AtomicReference<>();
		Thread waiting = new Thread(() -> {
			try {
				link.friends(STEVE, ALEX, 42, 20_000, call);
			} catch (Exception e) {
				thrown.set(e);
			}
		});
		waiting.start();
		long until = System.currentTimeMillis() + 5000;
		while (requests.size() < 2 && System.currentTimeMillis() < until) {
			Thread.sleep(5);
		}
		JsonObject held = requests.get(1);
		assertEquals(42, held.get("since").getAsLong());
		assertEquals(20_000, held.get("waitMs").getAsInt());
		long cut = System.currentTimeMillis();
		call.cancel();
		waiting.join(5000);
		assertTrue(System.currentTimeMillis() - cut < 2000, "cut short at once");
		assertEquals(LauncherLink.Call.CANCELLED, thrown.get().getMessage());
		assertFalse(((LauncherLink.LinkException) thrown.get()).unreachable(), "cut short isn't Flint gone");
		release.countDown();
		// A Flint from before 1.0.4 gives no revision: -1, polled as before.
		assertEquals(-1, SocialLink.revision(JsonParser.parseString("{\"ok\":true}").getAsJsonObject()));
		assertEquals(-1, SocialLink.revision(JsonParser.parseString("{\"revision\":\"x\"}").getAsJsonObject()));
		// A call cut short before it starts never connects.
		LauncherLink.Call early = new LauncherLink.Call();
		early.cancel();
		int asked = requests.size();
		assertThrows(LauncherLink.LinkException.class, () -> link.friends(STEVE, null, 42, 20_000, early));
		assertEquals(asked, requests.size());
	}

	@Test
	void actionsGoAsThePagesOwnAndFailuresCarryFlintsCode() throws Exception {
		SocialLink link = serve(r -> {
			JsonObject action = r.getAsJsonObject("action");
			return switch (action.get("kind").getAsString()) {
				case "send" -> "{\"ok\":true,\"message\":{\"id\":41,\"from\":\"" + STEVE + "\",\"to\":\"" + ALEX + "\",\"body\":\"" + action.get("body").getAsString()
					+ "\",\"sensitive\":" + action.get("sensitive").getAsBoolean() + "}}";
				case "request" -> "{\"ok\":false,\"error\":\"not_on_flint\"}";
				case "search" -> "{\"ok\":true,\"players\":[{\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\",\"relation\":\"outgoing\",\"accepts\":true}]}";
				case "history" -> "{\"ok\":true,\"complete\":true,\"messages\":[{\"id\":3,\"from\":\"" + ALEX + "\",\"to\":\"" + STEVE + "\",\"body\":\"old\"}]}";
				default -> "{\"ok\":true}";
			};
		});
		SocialLink.Message sent = link.send(STEVE, ALEX, "gg", true, "0c8f8c2e-6b0e-4d0e-9f43-0f6f3d8f6a11");
		assertEquals(41, sent.id());
		assertTrue(sent.sensitive());
		JsonObject request = requests.get(0);
		assertEquals("friendsAction", request.get("op").getAsString());
		assertEquals(STEVE, request.get("playing").getAsString());
		assertEquals("0c8f8c2e-6b0e-4d0e-9f43-0f6f3d8f6a11", request.getAsJsonObject("action").get("clientId").getAsString());
		LauncherLink.LinkException refused = assertThrows(LauncherLink.LinkException.class, () -> link.request(STEVE, "nobody"));
		assertEquals("not_on_flint", refused.getMessage());
		assertFalse(refused.unreachable());
		assertEquals("outgoing", link.search(STEVE, "ale").get(0).relation());
		SocialLink.Page page = link.history(STEVE, ALEX, 10);
		assertTrue(page.complete());
		assertEquals(10, requests.get(requests.size() - 1).getAsJsonObject("action").get("before").getAsLong());
		link.pin(STEVE, ALEX, true);
		JsonObject pin = requests.get(requests.size() - 1).getAsJsonObject("action");
		assertEquals("pin", pin.get("kind").getAsString());
		assertTrue(pin.get("pinned").getAsBoolean());
	}

	@Test
	void theStoreIsAskedForTheGamesAccount() throws Exception {
		SocialLink link = serve(r -> switch (r.get("op").getAsString()) {
			case "cosmetics" -> "{\"ok\":false,\"error\":\"Sign in to the Flint Store in the Flint app (its Store page) to wear your cosmetics here.\"}";
			default -> "{\"ok\":true,\"items\":[],\"equipped\":{}}";
		});
		LauncherLink.LinkException signedOut = assertThrows(LauncherLink.LinkException.class, () -> link.cosmetics(STEVE));
		assertTrue(signedOut.getMessage().startsWith("Sign in to the Flint Store"));
		link.equip(STEVE, "cape", null);
		JsonObject equip = requests.get(1);
		assertEquals("equip", equip.get("op").getAsString());
		assertEquals("cape", equip.get("slot").getAsString());
		assertFalse(equip.has("id"), "none: the slot is emptied");
	}

	@Test
	void anAnswerThatIsntOneIsRefusedNotFollowed() throws Exception {
		SocialLink link = serve(r -> "{\"ok\":\"yes\",\"phase\":\"ready\"}");
		assertThrows(LauncherLink.LinkException.class, () -> link.friends(STEVE, null), "ok must be true itself");
	}
}
