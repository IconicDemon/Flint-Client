package com.flintclient.client.module.account;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Music Player's requests over the game link, against a stand-in for Flint's listener (the wire format of gamelink.rs). */
class LauncherLinkMediaTest {
	private static final String SECRET = "cd".repeat(32);

	private ServerSocket server;
	private final List<JsonObject> requests = Collections.synchronizedList(new ArrayList<>());

	@AfterEach
	void close() throws Exception {
		if (server != null) {
			server.close();
		}
	}

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
	void whatIsPlayingIsRead() throws Exception {
		LauncherLink link = serve(request -> "{\"ok\":true,\"supported\":true,\"active\":true,\"playing\":true,\"title\":\"Midnight City\","
			+ "\"artist\":\"M83\",\"album\":\"Hurry Up\",\"app\":\"Spotify\",\"positionMs\":96000,\"durationMs\":243000,\"artVersion\":7,"
			+ "\"canPrevious\":true,\"canNext\":true,\"canToggle\":true,\"canSeek\":false}");
		LauncherLink.Media media = link.media(false);
		assertEquals(new LauncherLink.Media(true, null, true, true, "Midnight City", "M83", "Hurry Up", "Spotify", 96_000, 243_000, 7, true, true,
			true, false), media);
		assertEquals("media", requests.get(0).get("op").getAsString());
		assertFalse(requests.get(0).has("musicOnly"), "said only when on");
		link.media(true);
		assertTrue(requests.get(1).get("musicOnly").getAsBoolean());
		assertEquals(SECRET, requests.get(1).get("secret").getAsString());
	}

	@Test
	void oddTextAndMissingFieldsAreMadeSafe() {
		JsonObject reply = JsonParser.parseString("{\"ok\":true,\"supported\":true,\"active\":true,\"title\":\"A\\u0007B\\nC  \","
			+ "\"artist\":" + "\"" + "x".repeat(500) + "\",\"positionMs\":-5,\"durationMs\":\"soon\",\"playing\":\"yes\"}").getAsJsonObject();
		LauncherLink.Media media = LauncherLink.readMedia(reply);
		assertEquals("A B C", media.title(), "control characters become spaces, the ends are trimmed");
		assertEquals(200, media.artist().length(), "long text is cut");
		assertEquals("", media.album());
		assertEquals(0, media.positionMs());
		assertEquals(0, media.durationMs(), "not a number");
		assertFalse(media.playing(), "only a real true is true");
		assertNull(media.reason());
	}

	@Test
	void theArtComesAsPixelsOfTheSizeAskedFor() throws Exception {
		byte[] pixels = new byte[4 * 4 * 4];
		for (int i = 0; i < pixels.length; i++) {
			pixels[i] = (byte) i;
		}
		String rgba = Base64.getEncoder().encodeToString(pixels);
		LauncherLink link = serve(request -> "{\"ok\":true,\"version\":3,\"width\":4,\"height\":4,\"rgba\":\"" + rgba + "\"}");
		LauncherLink.Art art = link.mediaArt(4);
		assertEquals(3, art.version());
		assertEquals(4, art.width());
		assertArrayEquals(pixels, art.rgba());
		assertEquals(4, requests.get(0).get("size").getAsInt());
		link.mediaArt(10_000);
		assertEquals(LauncherLink.MAX_ART_SIZE, requests.get(1).get("size").getAsInt(), "never more than Flint keeps");
	}

	@Test
	void aBrokenPictureIsRefused() {
		assertThrows(LauncherLink.LinkException.class, () -> LauncherLink.readArt(JsonParser.parseString(
			"{\"version\":1,\"width\":4,\"height\":4,\"rgba\":\"AAAA\"}").getAsJsonObject()), "too few pixels");
		assertThrows(LauncherLink.LinkException.class, () -> LauncherLink.readArt(JsonParser.parseString(
			"{\"version\":1,\"width\":4,\"height\":4,\"rgba\":\"not base64!\"}").getAsJsonObject()));
		assertThrows(LauncherLink.LinkException.class, () -> LauncherLink.readArt(JsonParser.parseString(
			"{\"version\":1,\"width\":9000,\"height\":1,\"rgba\":\"\"}").getAsJsonObject()), "larger than any picture Flint sends");
	}

	@Test
	void buttonsArePressedAndAnOldFlintSaysSo() throws Exception {
		LauncherLink link = serve(request -> switch (request.get("action").getAsString()) {
			case "previous" -> "{\"ok\":false,\"error\":\"The app didn't take it\"}";
			case "eject" -> "{\"ok\":false,\"error\":\"bad request\"}";
			default -> "{\"ok\":true}";
		});
		link.mediaControl("toggle", 0, false);
		link.mediaControl("seek", 61_000, true);
		JsonObject seek = requests.get(1);
		assertEquals("mediaControl", seek.get("op").getAsString());
		assertEquals(61_000, seek.get("positionMs").getAsLong());
		assertTrue(seek.get("musicOnly").getAsBoolean());
		assertFalse(requests.get(0).has("positionMs"), "only a seek says where");
		LauncherLink.LinkException refused = assertThrows(LauncherLink.LinkException.class, () -> link.mediaControl("previous", 0, false));
		assertFalse(refused.unreachable());
		assertEquals("bad request", assertThrows(LauncherLink.LinkException.class, () -> link.mediaControl("eject", 0, false)).getMessage());
	}
}
