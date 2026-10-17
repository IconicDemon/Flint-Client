package com.flintclient.client.network;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.Nullable;

/**
 * Turns what Minecraft says about a disconnect into a {@link ConnectionOutcome}. Reasons are kept only as
 * vanilla translation keys from a fixed list (Minecraft 26.3's): a server can send any text or any key, and
 * only vanilla's own keys are known to carry nothing from the server.
 */
public final class DisconnectReasons {
	public static final String ABORTED = "connect.aborted";
	public static final String QUITTING = "multiplayer.status.quitting";
	public static final String UNKNOWN_HOST = "disconnect.unknownHost";
	/** What vanilla shows when the channel closed with no reason stored (the server dropped the socket). */
	public static final String GENERIC = "multiplayer.disconnect.generic";
	static final String GENERIC_REASON = "disconnect.genericReason";
	static final String LOGIN_FAILED = "disconnect.loginFailedInfo";

	private static final Set<String> VANILLA = Set.of(
		ABORTED,
		QUITTING,
		"multiplayer.requiredTexturePrompt.disconnect",
		UNKNOWN_HOST,
		"disconnect.endOfStream",
		"disconnect.exceeded_packet_rate",
		GENERIC_REASON,
		"disconnect.ignoring_status_request",
		LOGIN_FAILED,
		"disconnect.loginFailedInfo.insufficientPrivileges",
		"disconnect.loginFailedInfo.invalidSession",
		"disconnect.loginFailedInfo.serversUnavailable",
		"disconnect.loginFailedInfo.userBanned",
		"disconnect.packetError",
		"disconnect.spam",
		"disconnect.timeout",
		"disconnect.transfer",
		"multiplayer.disconnect.authservers_down",
		"multiplayer.disconnect.bad_chat_index",
		"multiplayer.disconnect.banned",
		"multiplayer.disconnect.banned.expiration",
		"multiplayer.disconnect.banned.reason",
		"multiplayer.disconnect.banned.reason.default",
		"multiplayer.disconnect.banned_ip.expiration",
		"multiplayer.disconnect.banned_ip.reason",
		"multiplayer.disconnect.chat_validation_failed",
		"multiplayer.disconnect.code_of_conduct",
		"multiplayer.disconnect.configuration_error",
		"multiplayer.disconnect.duplicate_login",
		"multiplayer.disconnect.expired_public_key",
		"multiplayer.disconnect.flying",
		GENERIC,
		"multiplayer.disconnect.idling",
		"multiplayer.disconnect.illegal_characters",
		"multiplayer.disconnect.incompatible",
		"multiplayer.disconnect.invalid_entity_attacked",
		"multiplayer.disconnect.invalid_packet",
		"multiplayer.disconnect.invalid_player_data",
		"multiplayer.disconnect.invalid_player_movement",
		"multiplayer.disconnect.invalid_public_key_signature",
		"multiplayer.disconnect.invalid_public_key_signature.new",
		"multiplayer.disconnect.invalid_vehicle_movement",
		"multiplayer.disconnect.ip_banned",
		"multiplayer.disconnect.kicked",
		"multiplayer.disconnect.missing_tags",
		"multiplayer.disconnect.name_taken",
		"multiplayer.disconnect.not_whitelisted",
		"multiplayer.disconnect.out_of_order_chat",
		"multiplayer.disconnect.outdated_client",
		"multiplayer.disconnect.outdated_server",
		"multiplayer.disconnect.server_full",
		"multiplayer.disconnect.server_shutdown",
		"multiplayer.disconnect.slow_login",
		"multiplayer.disconnect.too_many_pending_chats",
		"multiplayer.disconnect.transfers_disabled",
		"multiplayer.disconnect.unexpected_query_response",
		"multiplayer.disconnect.unsigned_chat",
		"multiplayer.disconnect.unverified_username"
	);
	// The client's own reasons. A server can put them in its disconnect packet too; then they're just a kick.
	private static final Set<String> CLIENT_ONLY = Set.of(ABORTED, QUITTING, "multiplayer.requiredTexturePrompt.disconnect",
		"disconnect.transfer", UNKNOWN_HOST);
	// How deep a failure's causes are searched; Netty wraps a connect failure at most once or twice.
	private static final int CAUSE_DEPTH = 8;

	private DisconnectReasons() {
	}

	/** {@code key} when it's one of vanilla's disconnect reason keys, otherwise null. */
	public static @Nullable String vanillaKey(@Nullable String key) {
		return key != null && VANILLA.contains(key) ? key : null;
	}

	/** Vanilla keys whose first argument is the specific reason ("Connection Lost: %s"), which says more. */
	public static boolean wrapsReason(String key) {
		return GENERIC_REASON.equals(key) || LOGIN_FAILED.equals(key);
	}

	/**
	 * The outcome for a disconnect with this reason key (null when the reason wasn't a vanilla key).
	 * {@code kicked} says the server sent a disconnect packet; keys that name a cause win over it
	 * (a server's keep-alive timeout arrives as a kick with {@code disconnect.timeout}), except the
	 * client's own reasons, which a server can only borrow.
	 */
	public static ConnectionOutcome outcome(@Nullable String key, boolean kicked) {
		if (kicked && key != null && CLIENT_ONLY.contains(key)) {
			return ConnectionOutcome.KICKED;
		}
		ConnectionOutcome named = key == null ? null : switch (key) {
			case ABORTED -> ConnectionOutcome.CANCELLED;
			case QUITTING, "multiplayer.requiredTexturePrompt.disconnect" -> ConnectionOutcome.QUIT;
			case "disconnect.transfer" -> ConnectionOutcome.TRANSFERRED;
			case UNKNOWN_HOST -> ConnectionOutcome.UNKNOWN_HOST;
			case "disconnect.timeout" -> ConnectionOutcome.TIMED_OUT;
			case "multiplayer.disconnect.server_shutdown" -> ConnectionOutcome.SERVER_CLOSED;
			case "multiplayer.disconnect.unverified_username", "multiplayer.disconnect.authservers_down" -> ConnectionOutcome.AUTH_FAILED;
			default -> key.startsWith(LOGIN_FAILED) ? ConnectionOutcome.AUTH_FAILED : null;
		};
		if (named != null) {
			return named;
		}
		if (kicked) {
			return ConnectionOutcome.KICKED;
		}
		if (key == null) {
			return ConnectionOutcome.UNKNOWN;
		}
		return switch (key) {
			case GENERIC, "disconnect.endOfStream" -> ConnectionOutcome.SERVER_CLOSED;
			case GENERIC_REASON, "disconnect.packetError" -> ConnectionOutcome.NETWORK_ERROR;
			// Every other vanilla reason is one a server sends.
			default -> VANILLA.contains(key) ? ConnectionOutcome.KICKED : ConnectionOutcome.UNKNOWN;
		};
	}

	/** The outcome for a socket connect that failed with {@code failure}, from its class and its causes' (never the message). */
	public static ConnectionOutcome outcome(@Nullable Throwable failure) {
		Throwable cause = failure;
		for (int depth = 0; cause != null && depth < CAUSE_DEPTH; depth++, cause = cause.getCause()) {
			if (cause instanceof CancellationException) {
				return ConnectionOutcome.CANCELLED;
			}
			// Netty's connect timeout extends ConnectException, so it's checked first.
			if (cause instanceof SocketTimeoutException || isA(cause, "io.netty.channel.ConnectTimeoutException")) {
				return ConnectionOutcome.CONNECT_TIMEOUT;
			}
			if (cause instanceof ConnectException) {
				return ConnectionOutcome.REFUSED;
			}
			if (cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) {
				return ConnectionOutcome.UNKNOWN_HOST;
			}
		}
		return ConnectionOutcome.NETWORK_ERROR;
	}

	// By name, so this stays free of a Netty dependency.
	private static boolean isA(Throwable error, String className) {
		for (Class<?> type = error.getClass(); type != null; type = type.getSuperclass()) {
			if (type.getName().equals(className)) {
				return true;
			}
		}
		return false;
	}
}
