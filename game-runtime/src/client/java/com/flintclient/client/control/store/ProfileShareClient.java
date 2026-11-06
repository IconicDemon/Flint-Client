package com.flintclient.client.control.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.jspecify.annotations.Nullable;

/**
 * Short profile codes through Flint's servers ({@link FlintServices}): {@link #create} stores a profile (the JSON a long
 * code holds, {@link ShareCodes#payload}) and answers its 6-character code; {@link #fetch} answers the profile JSON a code
 * holds. Both run off the game's thread with short timeouts, never follow redirects, and read at most
 * {@link #MAX_ANSWER_BYTES} of an answer. What comes back is untrusted: a code is checked against {@link ShortCodes}, and a
 * profile is only handed on as text, for {@link LayoutStore#importText} to read exactly as a long code's. Version-neutral.
 */
public final class ProfileShareClient {
	/** The most a stored profile may take (the server refuses more). */
	public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
	/** The most of an answer read; a longer one is cut off and counts as a failure. */
	public static final int MAX_ANSWER_BYTES = 128 * 1024;
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

	/** How a request ended. */
	public enum Status {
		/** Done: a code was made, or the profile came back. */
		OK,
		/** This Flint was built without the services. */
		NOT_CONFIGURED,
		/** Flint's servers couldn't be reached (no connection, a timeout). */
		OFFLINE,
		/** No profile has that code (never made, or more than 30 days old). */
		NOT_FOUND,
		/** The server is limiting requests for now. */
		BUSY,
		/** The server refused the profile (too big, or not a profile). */
		REFUSED,
		/** Anything else: an answer Flint doesn't understand. */
		FAILED
	}

	/** What a request gave: for {@link Status#OK}, the code ({@link #create}) or the profile JSON ({@link #fetch}). */
	public record Result(Status status, @Nullable String value) {
		static Result of(Status status) {
			return new Result(status, null);
		}

		public boolean ok() {
			return status == Status.OK && value != null;
		}
	}

	/** An HTTP answer: its status code and its body (cut at {@link #MAX_ANSWER_BYTES}). */
	public record Reply(int status, String body) {
	}

	/** Sends one POST. The real one is {@link #http()}; tests give their own. */
	public interface Transport {
		CompletableFuture<Reply> post(URI uri, Map<String, String> headers, String body);
	}

	private final @Nullable FlintServices services;
	private final Transport transport;

	public ProfileShareClient(@Nullable FlintServices services, Transport transport) {
		this.services = services;
		this.transport = transport;
	}

	private static volatile @Nullable ProfileShareClient shared;

	/** The client for the services this jar was built with (made on first use). */
	public static ProfileShareClient get() {
		ProfileShareClient client = shared;
		if (client == null) {
			client = new ProfileShareClient(FlintServices.bundled(), http());
			shared = client;
		}
		return client;
	}

	/** Whether short codes can be made and read at all (the jar was built with the services). */
	public boolean configured() {
		return services != null;
	}

	/** Stores {@code payloadJson} (a profile as {@link ShareCodes#payload} makes it) and answers its new code. */
	public CompletableFuture<Result> create(String payloadJson) {
		if (services == null) {
			return CompletableFuture.completedFuture(Result.of(Status.NOT_CONFIGURED));
		}
		JsonElement payload;
		try {
			payload = JsonParser.parseString(payloadJson);
		} catch (RuntimeException e) {
			return CompletableFuture.completedFuture(Result.of(Status.REFUSED));
		}
		if (!payload.isJsonObject() || payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
			return CompletableFuture.completedFuture(Result.of(Status.REFUSED));
		}
		JsonObject body = new JsonObject();
		body.add("p_payload", payload);
		return call("create_profile_share", body).thenApply(ProfileShareClient::readCreated)
			.exceptionally(ProfileShareClient::failure);
	}

	/** The profile JSON short code {@code text} holds (see {@link ShortCodes#parse}). */
	public CompletableFuture<Result> fetch(String text) {
		String code = ShortCodes.parse(text);
		if (code == null) {
			return CompletableFuture.completedFuture(Result.of(Status.NOT_FOUND));
		}
		if (services == null) {
			return CompletableFuture.completedFuture(Result.of(Status.NOT_CONFIGURED));
		}
		JsonObject body = new JsonObject();
		body.add("p_code", new JsonPrimitive(code));
		return call("get_profile_share", body).thenApply(ProfileShareClient::readFetched)
			.exceptionally(ProfileShareClient::failure);
	}

	private CompletableFuture<Reply> call(String function, JsonObject body) {
		FlintServices s = services;
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("apikey", s.anonKey());
		headers.put("Authorization", "Bearer " + s.anonKey());
		headers.put("Content-Type", "application/json");
		headers.put("Accept", "application/json");
		try {
			return transport.post(s.rpc(function), headers, body.toString());
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** {@code {"ok": true, "code": "K7QX3M", ...}}, or {@code {"ok": false, "error": ...}}. */
	static Result readCreated(Reply reply) {
		Status http = httpStatus(reply);
		if (http != null) {
			return Result.of(http);
		}
		JsonObject answer = object(reply.body());
		if (answer == null) {
			return Result.of(Status.FAILED);
		}
		if (Boolean.TRUE.equals(JsonFields.bool(answer, "ok"))) {
			String code = ShortCodes.parse(JsonFields.string(answer, "code"));
			return code == null ? Result.of(Status.FAILED) : new Result(Status.OK, code);
		}
		String error = JsonFields.string(answer, "error");
		if ("rate_limited".equals(error) || "busy".equals(error)) {
			return Result.of(Status.BUSY);
		}
		if ("invalid".equals(error) || "too_large".equals(error)) {
			return Result.of(Status.REFUSED);
		}
		return Result.of(Status.FAILED);
	}

	/** The profile (a JSON object) or {@code null} for an unknown or expired code. */
	static Result readFetched(Reply reply) {
		Status http = httpStatus(reply);
		if (http != null) {
			return Result.of(http);
		}
		String body = reply.body().strip();
		if (body.equals("null")) {
			return Result.of(Status.NOT_FOUND);
		}
		JsonObject answer = object(body);
		if (answer == null) {
			return Result.of(Status.FAILED);
		}
		return new Result(Status.OK, answer.toString());
	}

	/** What a status other than 200 means, or null for a 200. */
	private static @Nullable Status httpStatus(Reply reply) {
		int code = reply.status();
		if (code == 200) {
			return null;
		}
		if (code == 429 || code == 503 || code == 400 && reply.body().contains("rate_limited")) {
			return Status.BUSY;
		}
		return Status.FAILED;
	}

	private static @Nullable JsonObject object(String body) {
		if (body.length() > MAX_ANSWER_BYTES) {
			return null;
		}
		try {
			JsonElement parsed = JsonParser.parseString(body);
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** A request that didn't get an answer: no connection or a timeout is "offline"; anything else a failure. */
	static Result failure(Throwable error) {
		Throwable cause = error;
		while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return Result.of(cause instanceof IOException ? Status.OFFLINE : Status.FAILED);
	}

	// ---------- the real transport ----------

	/** Java's HTTP client: short timeouts, no redirects, answers cut at {@link #MAX_ANSWER_BYTES}. */
	public static Transport http() {
		HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
		return (uri, headers, body) -> {
			HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
			headers.forEach(request::header);
			return client.sendAsync(request.build(), info -> new CappedBody(MAX_ANSWER_BYTES))
				.thenApply(response -> new Reply(response.statusCode(), response.body()));
		};
	}

	/** A body read as UTF-8 that fails (and stops the download) once it passes {@code max} bytes. */
	static final class CappedBody implements HttpResponse.BodySubscriber<String> {
		private final int max;
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final CompletableFuture<String> result = new CompletableFuture<>();
		private Flow.@Nullable Subscription subscription;

		CappedBody(int max) {
			this.max = max;
		}

		@Override
		public CompletionStage<String> getBody() {
			return result;
		}

		@Override
		public void onSubscribe(Flow.Subscription s) {
			subscription = s;
			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			if (result.isDone()) {
				return;
			}
			for (ByteBuffer item : items) {
				if (bytes.size() + (long) item.remaining() > max) {
					if (subscription != null) {
						subscription.cancel();
					}
					// Not an IOException: an answer this big is a failure, not "offline".
					result.completeExceptionally(new IllegalStateException("answer larger than " + max + " bytes"));
					return;
				}
				byte[] chunk = new byte[item.remaining()];
				item.get(chunk);
				bytes.write(chunk, 0, chunk.length);
			}
		}

		@Override
		public void onError(Throwable error) {
			result.completeExceptionally(error);
		}

		@Override
		public void onComplete() {
			result.complete(bytes.toString(StandardCharsets.UTF_8));
		}
	}
}
