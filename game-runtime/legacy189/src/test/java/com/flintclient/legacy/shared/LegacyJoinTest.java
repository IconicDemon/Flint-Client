package com.flintclient.legacy.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LegacyJoinTest {
	private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
	private static final String BUSY = "{ \"path\" : \"/session/minecraft/join\" }";
	private static final String INVALID = "{ \"error\" : \"ForbiddenOperationException\", \"errorMessage\" : \"Invalid token\", \"path\" : \"/session/minecraft/join\" }";

	/** Answers in order (an exception where the server couldn't be reached), on a clock that moves with the pauses. */
	private static final class Fake {
		final Deque<Object> answers = new ArrayDeque<>();
		final List<String> sent = new ArrayList<>();
		final List<Long> pauses = new ArrayList<>();
		final List<String> log = new ArrayList<>();
		final AtomicLong now = new AtomicLong();

		Fake answer(Object... in) {
			answers.addAll(List.of(in));
			return this;
		}

		void join() throws AuthenticationException {
			LegacyJoin.join(json -> {
				sent.add(json);
				Object next = answers.isEmpty() ? new LegacyJoin.Answer(204, "") : answers.poll();
				if (next instanceof IOException e) {
					throw e;
				}
				return (LegacyJoin.Answer) next;
			}, "secret-token", STEVE, "-5c1a2b", log::add, ms -> {
				pauses.add(ms);
				now.addAndGet(ms);
			}, now::get);
		}
	}

	@Test
	void theRequestIsAuthlibsOwn() throws Exception {
		Fake fake = new Fake();
		fake.join();
		JsonObject sent = new JsonParser().parse(fake.sent.get(0)).getAsJsonObject();
		assertEquals("secret-token", sent.get("accessToken").getAsString());
		assertEquals("8667ba71b85a4004af54457a9734eed7", sent.get("selectedProfile").getAsString());
		assertEquals("-5c1a2b", sent.get("serverId").getAsString());
		assertTrue(fake.log.isEmpty(), "a join that goes through says nothing");
	}

	@Test
	void a503IsNotTakenForAJoinAndIsTriedAgainSecondsLater() throws Exception {
		Fake fake = new Fake().answer(new LegacyJoin.Answer(503, BUSY), new LegacyJoin.Answer(503, BUSY), new LegacyJoin.Answer(204, ""));
		fake.join();
		assertEquals(3, fake.sent.size());
		assertEquals(List.of(1_000L, 2_000L), fake.pauses);
		assertTrue(fake.log.get(0).contains("answered 503"));
		assertFalse(String.join(" ", fake.log).contains("secret-token"), "the token is never logged");
	}

	@Test
	void mojangLimitingJoinsOrNoAnswerIsTriedAgain() throws Exception {
		Fake fake = new Fake().answer(new LegacyJoin.Answer(429, BUSY), new SocketTimeoutException("Read timed out"), new LegacyJoin.Answer(204, ""));
		fake.join();
		assertEquals(3, fake.sent.size());
	}

	@Test
	void aServerThatStaysBusyIsReportedAsUnreachableAfterFiveTries() {
		Fake fake = new Fake();
		for (int i = 0; i < 10; i++) {
			fake.answer(new LegacyJoin.Answer(503, BUSY));
		}
		assertThrows(AuthenticationUnavailableException.class, fake::join);
		assertEquals(5, fake.sent.size());
		assertTrue(fake.pauses.stream().mapToLong(Long::longValue).sum() <= LegacyJoin.BUDGET_MS);
	}

	@Test
	void anInvalidSessionIsRefusedAtOnceAsAuthlibRefusesIt() {
		Fake fake = new Fake().answer(new LegacyJoin.Answer(403, INVALID));
		InvalidCredentialsException refused = assertThrows(InvalidCredentialsException.class, fake::join);
		assertEquals("Invalid token", refused.getMessage());
		assertEquals(1, fake.sent.size());
		Fake other = new Fake().answer(new LegacyJoin.Answer(403, "{\"error\":\"InsufficientPrivilegesException\",\"errorMessage\":\"Multiplayer is disabled\"}"));
		AuthenticationException thrown = assertThrows(AuthenticationException.class, other::join);
		assertEquals("Multiplayer is disabled", thrown.getMessage());
	}

	/** The real request against a local stand-in for the session server: status, body and headers as authlib sends them. */
	@Test
	void overHttp() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		List<String> bodies = new ArrayList<>();
		AtomicLong calls = new AtomicLong();
		server.createContext("/session/minecraft/join", exchange -> {
			bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) + " " + exchange.getRequestHeaders().getFirst("Content-Type"));
			boolean busy = calls.incrementAndGet() == 1;
			byte[] body = (busy ? BUSY : "").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(busy ? 503 : 204, busy ? body.length : -1);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		try {
			List<String> log = new ArrayList<>();
			LegacyJoin.join(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/session/minecraft/join").toURL(), Proxy.NO_PROXY, "secret-token", STEVE, "abc", log::add);
			assertEquals(2, calls.get(), "the 503 was tried again, the 204 ended it");
			assertTrue(bodies.get(0).contains("\"selectedProfile\":\"8667ba71b85a4004af54457a9734eed7\"") && bodies.get(0).endsWith("application/json; charset=utf-8"));
			assertTrue(log.get(log.size() - 1).contains("try 2 of 5"));
		} finally {
			server.stop(0);
		}
	}
}
